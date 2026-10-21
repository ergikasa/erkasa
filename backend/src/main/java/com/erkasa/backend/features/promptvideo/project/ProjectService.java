package com.erkasa.backend.features.promptvideo.project;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.erkasa.backend.assets.AssetStorage;
import com.erkasa.backend.common.error.ApiException;
import com.erkasa.backend.features.promptvideo.render.RenderJobService;
import com.erkasa.backend.features.promptvideo.script.ScriptValidator;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.InvalidTransitionException;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.StepStatus;
import com.erkasa.backend.jobs.JobStep;
import com.erkasa.backend.jobs.JobStepRepository;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;
import com.erkasa.backend.jobs.StepType;
import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.tts.TtsVoiceRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * What a user can do with a video project, step by step:
 * create -> (script is written in the background) -> edit / regenerate -> approve, or cancel.
 *
 * Every change locks the project row first (see ProjectRepository.lockById), and the state
 * machine in ProjectStatus refuses moves that make no sense (approving twice, editing a
 * cancelled project...). Those become HTTP 409.
 */
@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    /** A user can ask for a new script this many times per project: each one costs us an LLM call. */
    static final int MAX_SCRIPT_JOBS = 5;

    /** "Try again" stops after a step has failed this many times: something is wrong with that scene. */
    static final int MAX_STEP_ATTEMPTS = 3;

    /** The first assets job + up to 9 scene redos per project. */
    static final int MAX_ASSETS_JOBS = 10;

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final TtsVoiceRepository voices;
    private final ScriptJobRunner scriptRunner;
    private final AssetsJobRunner assetsRunner;
    private final AssetStorage storage;
    private final ProjectEvents events;
    private final RenderJobService renders;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public ProjectService(ProjectRepository projects, JobRepository jobs, JobStepRepository steps,
                          TtsVoiceRepository voices, ScriptJobRunner scriptRunner,
                          AssetsJobRunner assetsRunner, AssetStorage storage, ProjectEvents events,
                          RenderJobService renders, TransactionTemplate tx, ObjectMapper json) {
        this.projects = projects;
        this.jobs = jobs;
        this.steps = steps;
        this.voices = voices;
        this.scriptRunner = scriptRunner;
        this.assetsRunner = assetsRunner;
        this.storage = storage;
        this.events = events;
        this.renders = renders;
        this.tx = tx;
        this.json = json;
    }

    public record NewProject(String idea, Language language, int durationSeconds,
                             AspectRatio aspectRatio, String voiceId) {
    }

    /** Saves the project and its first SCRIPT job, then starts writing the script in the background. */
    public Project create(UUID userId, NewProject input) {
        String voiceId = input.voiceId() == null || input.voiceId().isBlank() ? null : input.voiceId().strip();
        if (voiceId != null && voices.findFirstByLanguageAndVoiceIdAndActiveTrue(input.language(), voiceId).isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_VOICE",
                    "Voice " + voiceId + " is not available for " + input.language());
        }
        AspectRatio ratio = input.aspectRatio() == null ? AspectRatio.PORTRAIT : input.aspectRatio();

        Created created = tx.execute(status -> {
            Project project = projects.save(Project.create(userId, input.idea().strip(), input.language(),
                    input.durationSeconds(), ratio, voiceId));
            Job job = queueScriptJob(project.getId());
            return new Created(project, job.getId());
        });
        // Only after commit: the background thread must be able to see the rows.
        scriptRunner.start(created.jobId());
        log.info("Project {} created by user {}", created.project().getId(), userId);
        return created.project();
    }

    private record Created(Project project, UUID jobId, JobKind kind) {

        Created(Project project, UUID jobId) {
            this(project, jobId, null);
        }
    }

    public Project get(UUID userId, UUID projectId) {
        return projects.findByIdAndUserId(projectId, userId).orElseThrow(ProjectService::notFound);
    }

    public List<Project> list(UUID userId, int limit) {
        return projects.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    public List<Job> jobsOf(UUID projectId) {
        return jobs.findByProjectIdOrderByCreatedAtAsc(projectId);
    }

    /** The user changed the script during review. It must pass the same checks as the LLM's script. */
    public Project updateScript(UUID userId, UUID projectId, VideoScript script) {
        return notify(tx.execute(status -> {
            Project project = lockOwned(userId, projectId);
            if (project.getStatus() != ProjectStatus.SCRIPT_REVIEW) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                        "The script can only be edited while it is in review (now " + project.getStatus() + ")");
            }
            List<String> problems = ScriptValidator.validate(script, project.getDurationSeconds());
            if (!problems.isEmpty()) {
                throw new InvalidScriptException(problems);
            }
            project.setScript(script.title(), json.writeValueAsString(script));
            return project;
        }));
    }

    /** Throws the current script away and writes a new one. */
    public Project regenerateScript(UUID userId, UUID projectId) {
        Created created = tx.execute(status -> {
            Project project = lockOwned(userId, projectId);
            if (project.getStatus() == ProjectStatus.FAILED && project.getScript() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                        "This project failed after its script was approved; retry that stage instead");
            }
            if (jobs.countByProjectIdAndKind(projectId, JobKind.SCRIPT) >= MAX_SCRIPT_JOBS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_SCRIPTS",
                        "A project can have at most " + MAX_SCRIPT_JOBS + " scripts. Edit this one or start a new project.");
            }
            project.moveTo(ProjectStatus.SCRIPT_PENDING);
            // The old script is gone: until the new one arrives, GET shows script = null.
            project.setScript(null, null);
            Job job = queueScriptJob(projectId);
            return new Created(project, job.getId());
        });
        scriptRunner.start(created.jobId());
        return notify(created.project());
    }

    /**
     * The user is happy with the script: creates the ASSETS job with one IMAGE and one VOICE
     * step per scene, then starts it in the background.
     */
    public Project approve(UUID userId, UUID projectId) {
        Created created = tx.execute(status -> {
            Project project = lockOwned(userId, projectId);
            // Only from review: ASSETS_READY -> ASSETS_PENDING is also a legal move (redo a scene),
            // but "approve" must not start a second full ASSETS job.
            if (project.getStatus() != ProjectStatus.SCRIPT_REVIEW) {
                throw new InvalidTransitionException("project " + projectId, project.getStatus(),
                        ProjectStatus.ASSETS_PENDING);
            }
            project.moveTo(ProjectStatus.ASSETS_PENDING);
            VideoScript script = scriptOf(project);
            if (script == null || script.scenes() == null || script.scenes().isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "NO_SCRIPT", "This project has no script to approve");
            }
            Job job = jobs.save(Job.queue(projectId, JobKind.ASSETS));
            for (int scene = 0; scene < script.scenes().size(); scene++) {
                // Motion-graphics scenes are drawn by the render worker: no image to generate (or pay for).
                if (script.scenes().get(scene).needsImage()) {
                    steps.save(JobStep.forScene(job.getId(), StepType.IMAGE, scene));
                }
                steps.save(JobStep.forScene(job.getId(), StepType.VOICE, scene));
            }
            log.info("Project {} script approved: {} scenes", projectId, script.scenes().size());
            return new Created(project, job.getId());
        });
        assetsRunner.start(created.jobId());
        return notify(created.project());
    }

    /** Every IMAGE / VOICE step of the latest ASSETS job, scene by scene (empty before approval). */
    public List<JobStep> assetStepsOf(UUID userId, UUID projectId) {
        get(userId, projectId); // 404 for other users
        return jobs.findFirstByProjectIdAndKindOrderByCreatedAtDesc(projectId, JobKind.ASSETS)
                .map(job -> steps.findByJobIdOrderByTypeAscSceneIndexAsc(job.getId()))
                .orElse(List.of());
    }

    /** The bytes of one scene's image or voice, or 404 if it does not exist (yet). */
    public Asset asset(UUID userId, UUID projectId, int scene, StepType type) {
        JobStep step = assetStepsOf(userId, projectId).stream()
                .filter(s -> s.getType() == type && s.getSceneIndex() == scene && s.getOutputKey() != null)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND",
                        "No " + type.name().toLowerCase() + " for scene " + scene + " yet"));
        byte[] data = storage.get(step.getOutputKey());
        if (data == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "The file is missing from storage");
        }
        return new Asset(data, AssetStorage.contentTypeOf(step.getOutputKey()));
    }

    public record Asset(byte[] data, String contentType) {
    }

    /**
     * "Try again" on a FAILED project. Restarts only the stage that failed:
     * - script failed -> a new SCRIPT job (counts toward the 5-script limit)
     * - assets failed -> the SAME assets job again, but only its FAILED steps; scenes that
     *   already succeeded are kept and never paid for twice
     */
    public Project retry(UUID userId, UUID projectId) {
        Created restarted = tx.execute(status -> {
            Project project = lockOwned(userId, projectId);
            if (project.getStatus() != ProjectStatus.FAILED) {
                throw new InvalidTransitionException("project " + projectId, project.getStatus(), ProjectStatus.FAILED);
            }
            List<Job> history = jobs.findByProjectIdOrderByCreatedAtAsc(projectId);
            Job last = history.get(history.size() - 1);
            if (last.getKind() == JobKind.RENDER) {
                // the files are fine, only the MP4 failed: send the same render job again
                renders.requeueFailed(last);
                project.moveTo(ProjectStatus.RENDERING);
                return new Created(project, last.getId(), JobKind.RENDER);
            }
            if (last.getKind() != JobKind.ASSETS) {
                return null; // the script stage failed: handled by regenerateScript below
            }
            List<JobStep> failed = steps.findByJobIdAndStatusInOrderByTypeAscSceneIndexAsc(
                    last.getId(), List.of(StepStatus.FAILED));
            for (JobStep step : failed) {
                if (step.getAttempts() >= MAX_STEP_ATTEMPTS) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_RETRIES",
                            step.getType() + " of scene " + (step.getSceneIndex() + 1) + " failed "
                                    + step.getAttempts() + " times. Change that scene and redo it, or start a new project.");
                }
                step.moveTo(StepStatus.PENDING);
            }
            last.moveTo(JobStatus.QUEUED);
            project.moveTo(ProjectStatus.ASSETS_PENDING);
            log.info("Project {} retry: {} failed steps of assets job {}", projectId, failed.size(), last.getId());
            return new Created(project, last.getId(), JobKind.ASSETS);
        });
        if (restarted == null) {
            return regenerateScript(userId, projectId);
        }
        if (restarted.kind() == JobKind.RENDER) {
            renders.send(restarted.jobId());
        } else {
            assetsRunner.start(restarted.jobId());
        }
        return notify(restarted.project());
    }

    /**
     * The user does not like one scene's image and/or voice. Optionally with a new image prompt
     * or new narration for that scene. Creates a new ASSETS job where only that scene is redone;
     * every other scene's files are reused from the previous job (no cost).
     *
     * @param scene from 0, like the asset URLs
     */
    public Project redoScene(UUID userId, UUID projectId, int scene, boolean image, boolean voice,
                             String newImagePrompt, String newNarration) {
        Created created = tx.execute(status -> {
            Project project = lockOwned(userId, projectId);
            if (project.getStatus() != ProjectStatus.ASSETS_READY) {
                throw new InvalidTransitionException("project " + projectId, project.getStatus(),
                        ProjectStatus.ASSETS_PENDING);
            }
            VideoScript script = scriptOf(project);
            if (script == null || scene < 0 || scene >= script.scenes().size()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "SCENE_NOT_FOUND", "There is no scene " + scene);
            }
            if (jobs.countByProjectIdAndKind(projectId, JobKind.ASSETS) >= MAX_ASSETS_JOBS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REDOS",
                        "A project can be redone at most " + (MAX_ASSETS_JOBS - 1) + " times");
            }

            boolean redoImage = image || hasText(newImagePrompt);
            boolean redoVoice = voice || hasText(newNarration);
            if (redoImage && !script.scenes().get(scene).needsImage()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NO_IMAGE_IN_SCENE",
                        "Scene " + scene + " shows motion graphics, not an image; only its voice can be redone");
            }
            if (!redoImage && !redoVoice) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "NOTHING_TO_REDO", "Choose the image, the voice, or both");
            }

            // A new prompt or narration changes the script itself.
            if (hasText(newImagePrompt) || hasText(newNarration)) {
                List<VideoScript.Scene> scenes = new java.util.ArrayList<>(script.scenes());
                VideoScript.Scene old = scenes.get(scene);
                scenes.set(scene, new VideoScript.Scene(
                        hasText(newNarration) ? newNarration.strip() : old.narration(),
                        old.onScreenText(),
                        hasText(newImagePrompt) ? newImagePrompt.strip() : old.imagePrompt(),
                        old.visual(),
                        old.sfx()));
                VideoScript changed = new VideoScript(script.title(), script.musicMood(), scenes);
                List<String> problems = ScriptValidator.validate(changed, project.getDurationSeconds());
                if (!problems.isEmpty()) {
                    throw new InvalidScriptException(problems);
                }
                project.setScript(changed.title(), json.writeValueAsString(changed));
            }

            Job previous = jobs.findFirstByProjectIdAndKindOrderByCreatedAtDesc(projectId, JobKind.ASSETS).orElseThrow();
            Job job = jobs.save(Job.queue(projectId, JobKind.ASSETS));
            for (JobStep old : steps.findByJobIdOrderByTypeAscSceneIndexAsc(previous.getId())) {
                boolean redo = old.getSceneIndex() == scene
                        && ((old.getType() == StepType.IMAGE && redoImage) || (old.getType() == StepType.VOICE && redoVoice));
                steps.save(redo
                        ? JobStep.forScene(job.getId(), old.getType(), old.getSceneIndex())
                        : JobStep.reuse(job.getId(), old));
            }
            project.moveTo(ProjectStatus.ASSETS_PENDING);
            log.info("Project {} redo scene {} (image={}, voice={})", projectId, scene, redoImage, redoVoice);
            return new Created(project, job.getId());
        });
        assetsRunner.start(created.jobId());
        return notify(created.project());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    public Project cancel(UUID userId, UUID projectId) {
        return notify(tx.execute(status -> {
            Project project = lockOwned(userId, projectId);
            project.moveTo(ProjectStatus.CANCELLED);
            log.info("Project {} cancelled", projectId);
            return project;
        }));
    }

    /** "Make the video" (5.2b): ASSETS_READY -> RENDERING, the worker makes the MP4. */
    public Project render(UUID userId, UUID projectId) {
        return renders.start(userId, projectId);
    }

    /** The finished MP4, or 404 while the project is not DONE. */
    public Asset video(UUID userId, UUID projectId) {
        get(userId, projectId); // 404 for other users
        String key = renders.videoKey(projectId);
        byte[] data = key == null ? null : storage.get(key);
        if (data == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "VIDEO_NOT_READY", "The video is not ready yet");
        }
        return new Asset(data, "video/mp4");
    }

    /** Live update for open browser tabs (4.5). Called after the change is committed. */
    private Project notify(Project project) {
        events.changed(project.getId());
        return project;
    }

    /** Opens the live update stream; 404 for other users' projects. */
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(UUID userId, UUID projectId) {
        get(userId, projectId);
        return events.subscribe(projectId);
    }

    /** The project's script as an object, or null while it is still being written. */
    public VideoScript scriptOf(Project project) {
        if (project.getScript() == null) {
            return null;
        }
        try {
            return json.readValue(project.getScript(), VideoScript.class);
        } catch (JacksonException e) {
            log.error("Project {} has a script that cannot be read", project.getId(), e);
            return null;
        }
    }

    private Job queueScriptJob(UUID projectId) {
        Job job = jobs.save(Job.queue(projectId, JobKind.SCRIPT));
        steps.save(JobStep.forVideo(job.getId(), StepType.SCRIPT));
        return job;
    }

    /** Locks the project, and makes other users' projects look like they do not exist. */
    private Project lockOwned(UUID userId, UUID projectId) {
        Project project = projects.lockById(projectId).orElseThrow(ProjectService::notFound);
        if (!project.getUserId().equals(userId)) {
            throw notFound();
        }
        return project;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found");
    }
}
