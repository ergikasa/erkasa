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
import com.erkasa.backend.features.promptvideo.script.ScriptValidator;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.InvalidTransitionException;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobRepository;
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

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final TtsVoiceRepository voices;
    private final ScriptJobRunner scriptRunner;
    private final AssetsJobRunner assetsRunner;
    private final AssetStorage storage;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public ProjectService(ProjectRepository projects, JobRepository jobs, JobStepRepository steps,
                          TtsVoiceRepository voices, ScriptJobRunner scriptRunner,
                          AssetsJobRunner assetsRunner, AssetStorage storage,
                          TransactionTemplate tx, ObjectMapper json) {
        this.projects = projects;
        this.jobs = jobs;
        this.steps = steps;
        this.voices = voices;
        this.scriptRunner = scriptRunner;
        this.assetsRunner = assetsRunner;
        this.storage = storage;
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

    private record Created(Project project, UUID jobId) {
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
        return tx.execute(status -> {
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
        });
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
        return created.project();
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
                steps.save(JobStep.forScene(job.getId(), StepType.IMAGE, scene));
                steps.save(JobStep.forScene(job.getId(), StepType.VOICE, scene));
            }
            log.info("Project {} script approved: {} scenes", projectId, script.scenes().size());
            return new Created(project, job.getId());
        });
        assetsRunner.start(created.jobId());
        return created.project();
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

    public Project cancel(UUID userId, UUID projectId) {
        return tx.execute(status -> {
            Project project = lockOwned(userId, projectId);
            project.moveTo(ProjectStatus.CANCELLED);
            log.info("Project {} cancelled", projectId);
            return project;
        });
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