package com.erkasa.backend.features.promptvideo.render;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.erkasa.backend.credits.CreditService;
import com.erkasa.backend.assets.AssetStorage;
import com.erkasa.backend.common.error.ApiException;
import com.erkasa.backend.features.promptvideo.project.ProjectEvents;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.InvalidTransitionException;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.JobStep;
import com.erkasa.backend.jobs.JobStepRepository;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;
import com.erkasa.backend.jobs.StepStatus;
import com.erkasa.backend.jobs.StepType;

import tools.jackson.databind.ObjectMapper;

/**
 * The RENDER stage (5.2b).
 *
 *   start()   ASSETS_READY -> RENDERING: a RENDER job with one RENDER step, sent to the worker
 *   onResult  PROGRESS: live % for the UI;  SUCCEEDED: step + job done, project DONE;
 *             FAILED: step + job + project FAILED (POST /retry sends it again)
 *
 * The MP4 lands at projects/{projectId}/{jobId}/video.mp4; the RENDER step's output_key points at it.
 */
@Service
public class RenderJobService {

    private static final Logger log = LoggerFactory.getLogger(RenderJobService.class);
    static final String VIDEO_TYPE = "video/mp4";

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final RenderSpecBuilder specs;
    private final AssetStorage storage;
    private final RabbitTemplate rabbit;
    private final ProjectEvents events;
    private final CreditService credits;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public RenderJobService(ProjectRepository projects, JobRepository jobs, JobStepRepository steps,
                            RenderSpecBuilder specs, AssetStorage storage, RabbitTemplate rabbit,
                            ProjectEvents events, TransactionTemplate tx, ObjectMapper json,
                            CreditService credits) {
        this.credits = credits;
        this.projects = projects;
        this.jobs = jobs;
        this.steps = steps;
        this.specs = specs;
        this.storage = storage;
        this.rabbit = rabbit;
        this.events = events;
        this.tx = tx;
        this.json = json;
    }

    /** "Make the video": the user is happy with every image and voice. */
    public Project start(UUID userId, UUID projectId) {
        Started started = tx.execute(status -> {
            Project p = projects.lockById(projectId)
                    .filter(found -> found.getUserId().equals(userId))
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "Project not found"));
            if (p.getStatus() != ProjectStatus.ASSETS_READY) {
                throw new InvalidTransitionException("project " + projectId, p.getStatus(), ProjectStatus.RENDERING);
            }
            p.moveTo(ProjectStatus.RENDERING);
            Job job = jobs.save(Job.queue(projectId, JobKind.RENDER));
            steps.save(JobStep.forVideo(job.getId(), StepType.RENDER));
            return new Started(p, job.getId());
        });
        send(started.jobId());
        events.changed(projectId);
        return started.project();
    }

    private record Started(Project project, UUID jobId) {
    }

    /**
     * Sends a QUEUED render job to the worker and marks it RUNNING. Also used by "retry" after a
     * failed render (the job and its step are moved back to QUEUED / PENDING first).
     */
    public void send(UUID jobId) {
        RenderMessages.Request request = tx.execute(status -> {
            Job job = jobs.findById(jobId).orElseThrow();
            if (job.getStatus() != JobStatus.QUEUED) {
                return null;
            }
            Project project = projects.findById(job.getProjectId()).orElseThrow();
            VideoScript script = json.readValue(project.getScript(), VideoScript.class);
            Job assets = jobs.findFirstByProjectIdAndKindOrderByCreatedAtDesc(project.getId(), JobKind.ASSETS).orElseThrow();
            RenderMessages.Spec spec = specs.build(project, script, steps.findByJobIdOrderByTypeAscSceneIndexAsc(assets.getId()));

            String key = "projects/" + project.getId() + "/" + jobId + "/video.mp4";
            String uploadUrl = storage.presignPut(key, VIDEO_TYPE, RenderSpecBuilder.LINK_VALID);

            job.moveTo(JobStatus.RUNNING);
            stepOf(jobId).moveTo(StepStatus.RUNNING);
            return new RenderMessages.Request(jobId, project.getId(), key, uploadUrl, spec);
        });
        if (request == null) {
            return;
        }
        Message message = MessageBuilder.withBody(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .build();
        rabbit.send("", RenderQueues.REQUESTS, message);
        log.info("Render job {} sent to the worker ({} scenes)", jobId, request.spec().scenes().size());
    }

    /** A FAILED render goes back to the worker; the caller has already locked the project. */
    public void requeueFailed(Job job) {
        JobStep step = stepOf(job.getId());
        if (step.getStatus() == StepStatus.FAILED) {
            step.moveTo(StepStatus.PENDING);
        }
        job.moveTo(JobStatus.QUEUED);
    }

    /** A message from the worker. Never throws: a bad message is logged, not retried forever. */
    void onResult(RenderMessages.Result result) {
        if (result == null || result.jobId() == null || result.type() == null) {
            log.warn("Ignoring render result without jobId/type: {}", result);
            return;
        }
        Job found = jobs.findById(result.jobId()).orElse(null);
        if (found == null) {
            log.warn("Render result for unknown job {}", result.jobId());
            return;
        }
        UUID projectId = found.getProjectId();
        switch (result.type()) {
            case RenderMessages.Result.PROGRESS -> events.renderProgress(projectId, result.percent() == null ? 0 : result.percent());
            case RenderMessages.Result.SUCCEEDED -> {
                tx.executeWithoutResult(status -> succeed(result));
                events.renderFinished(projectId);
            }
            case RenderMessages.Result.FAILED -> {
                tx.executeWithoutResult(status -> fail(result));
                events.renderFinished(projectId);
            }
            default -> log.warn("Unknown render result type {}", result.type());
        }
    }

    private void succeed(RenderMessages.Result r) {
        Job job = jobs.findById(r.jobId()).orElseThrow();
        Project project = projects.lockById(job.getProjectId()).orElseThrow();
        JobStep step = stepOf(job.getId());
        if (step.getStatus() != StepStatus.RUNNING) {
            log.info("Render job {} already finished ({}); duplicate result ignored", job.getId(), step.getStatus());
            return;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("contentType", VIDEO_TYPE);
        meta.put("durationSeconds", r.durationSeconds());
        meta.put("bytes", r.bytes());
        meta.put("renderSeconds", r.renderSeconds());
        step.succeed("projects/" + project.getId() + "/" + job.getId() + "/video.mp4",
                json.writeValueAsString(meta), BigDecimal.ZERO);
        job.moveTo(JobStatus.SUCCEEDED);
        if (project.getStatus() == ProjectStatus.RENDERING) {
            project.moveTo(ProjectStatus.DONE);
        }
        log.info("Render job {} done: {} s video, rendered in {} s", job.getId(), r.durationSeconds(), r.renderSeconds());
    }

    private void fail(RenderMessages.Result r) {
        Job job = jobs.findById(r.jobId()).orElseThrow();
        Project project = projects.lockById(job.getProjectId()).orElseThrow();
        JobStep step = stepOf(job.getId());
        if (step.getStatus() != StepStatus.RUNNING) {
            return;
        }
        String message = r.error() == null ? "The render worker failed" : r.error();
        step.fail("RENDER_FAILED", message, BigDecimal.ZERO);
        job.fail("RENDER_FAILED", message);
        if (project.getStatus() == ProjectStatus.RENDERING) {
            project.fail("RENDER_FAILED", message);
            credits.refundVideo(project.getUserId(), project.getId(), "Video failed: RENDER_FAILED");
        }
        log.warn("Render job {} failed: {}", job.getId(), message);
    }

    /** Where the finished MP4 of a DONE project is, or null. */
    public String videoKey(UUID projectId) {
        return jobs.findFirstByProjectIdAndKindOrderByCreatedAtDesc(projectId, JobKind.RENDER)
                .flatMap(job -> steps.findByJobIdAndTypeAndSceneIndex(job.getId(), StepType.RENDER, JobStep.WHOLE_VIDEO))
                .filter(step -> step.getStatus() == StepStatus.SUCCEEDED)
                .map(JobStep::getOutputKey)
                .orElse(null);
    }

    private JobStep stepOf(UUID jobId) {
        return steps.findByJobIdAndTypeAndSceneIndex(jobId, StepType.RENDER, JobStep.WHOLE_VIDEO).orElseThrow();
    }
}
