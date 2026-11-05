package com.erkasa.backend.features.promptvideo.render;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import com.erkasa.backend.outbox.Outbox;

import tools.jackson.databind.ObjectMapper;

/**
 * The RENDER stage (5.2b).
 *
 *   start()   ASSETS_READY -> RENDERING: a RENDER job with one RENDER step, sent to the worker
 *             through the outbox (V2 task A1)
 *   onResult  CLAIMED: the worker takes the lease;  PROGRESS: live % for the UI, and the heartbeat
 *             that keeps the lease;  SUCCEEDED: step + job done, project DONE;
 *             FAILED: step + job + project FAILED (POST /retry sends it again)
 *
 * Lease and fencing (V2 task A3): every send is a new dispatch (Job.newDispatch). A worker's
 * message changes the job only if Job.fence() accepts it: job RUNNING, current dispatch, and no
 * other worker holding an unexpired lease. Everything else is logged and ignored, so a worker
 * left over from an earlier dispatch can never finish (or fail) the project.
 * Every check runs with the project row locked (the same lock as cancel / retry).
 *
 * Time limits (V2 task A4): watch() is called by RenderWatchdog for every running render; see there.
 * cancelActive() closes the active render when the user cancels.
 *
 * The MP4 lands at projects/{projectId}/{jobId}/{dispatchId}/video.mp4 (one folder per dispatch,
 * so two uploads never overwrite each other); the RENDER step's output_key points at it.
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
    private final Outbox outbox;
    private final ProjectEvents events;
    private final CreditService credits;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;
    /** how long a CLAIMED or a heartbeat keeps the lease (the worker beats every 20 s) */
    private final Duration lease;
    /** render deadline = max(renderTimeoutMin, renderTimeoutPerSecond x video seconds) */
    private final Duration renderTimeoutMin;
    private final Duration renderTimeoutPerSecond;
    /** watchdog (V2 A4): no CLAIMED this long after the send -> RENDER_QUEUE_TIMEOUT */
    private final Duration queueTimeout;
    /** watchdog: fails at deadline + this; the worker already stops itself at 90 % */
    private final Duration deadlineGrace;
    /** watchdog: waits this long past an expired lease, so another worker's heartbeat can take it over first */
    private final Duration leaseGrace;
    /** watchdog: dispatches per job before a lost worker means FAILED (2 = one new send) */
    private final int maxAttempts;

    public RenderJobService(ProjectRepository projects, JobRepository jobs, JobStepRepository steps,
                            RenderSpecBuilder specs, AssetStorage storage, Outbox outbox,
                            ProjectEvents events, TransactionTemplate tx, ObjectMapper json,
                            CreditService credits, Clock clock,
                            @Value("${erkasa.render.lease:2m}") Duration lease,
                            @Value("${erkasa.render.timeout-min:10m}") Duration renderTimeoutMin,
                            @Value("${erkasa.render.timeout-per-video-second:6s}") Duration renderTimeoutPerSecond,
                            @Value("${erkasa.render.queue-timeout:30m}") Duration queueTimeout,
                            @Value("${erkasa.render.deadline-grace:1m}") Duration deadlineGrace,
                            @Value("${erkasa.render.lease-grace:30s}") Duration leaseGrace,
                            @Value("${erkasa.render.max-attempts:2}") int maxAttempts) {
        this.queueTimeout = queueTimeout;
        this.deadlineGrace = deadlineGrace;
        this.leaseGrace = leaseGrace;
        this.maxAttempts = maxAttempts;
        this.clock = clock;
        this.lease = lease;
        this.renderTimeoutMin = renderTimeoutMin;
        this.renderTimeoutPerSecond = renderTimeoutPerSecond;
        this.credits = credits;
        this.projects = projects;
        this.jobs = jobs;
        this.steps = steps;
        this.specs = specs;
        this.storage = storage;
        this.outbox = outbox;
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
     * Queues a QUEUED render job for the worker and marks it RUNNING. Also used by "retry" after a
     * failed render (the job and its step are moved back to QUEUED / PENDING first).
     *
     * The request goes into the outbox in the SAME transaction as the RUNNING change (V2 task A1),
     * so it reaches RabbitMQ even if RabbitMQ is down right now: OutboxRelay keeps trying.
     * Before, it was published after the commit, and a failed publish left the project RENDERING forever.
     */
    public void send(UUID jobId) {
        RenderMessages.Request request = tx.execute(status -> {
            // Project lock first (same order as cancel / fail), then read the job: two callers at
            // once (a retry and startup recovery, say) are serialized, and the second sees RUNNING.
            UUID projectId = jobs.findProjectIdById(jobId).orElseThrow();
            Project project = projects.lockById(projectId).orElseThrow();
            Job job = jobs.findById(jobId).orElseThrow();
            if (job.getStatus() != JobStatus.QUEUED) {
                return null;
            }
            if (project.getStatus() != ProjectStatus.RENDERING) {
                // e.g. cancelled while the job waited: never render it, close it instead (V2 A2)
                job.moveTo(JobStatus.CANCELLED);
                JobStep step = stepOf(jobId);
                if (step.getStatus().canMoveTo(StepStatus.SKIPPED)) {
                    step.moveTo(StepStatus.SKIPPED);
                }
                log.info("Render job {} not sent: project {} is {}", jobId, projectId, project.getStatus());
                return null;
            }
            job.moveTo(JobStatus.RUNNING);
            stepOf(jobId).moveTo(StepStatus.RUNNING);
            return dispatch(project, job);
        });
        if (request != null) {
            log.info("Render job {} queued for the worker: dispatch {}, attempt {}, {} scenes", jobId,
                    request.dispatchId(), request.attempt(), request.spec().scenes().size());
        }
    }

    /**
     * A new dispatch of a RUNNING job: fresh links, a new dispatch id, into the outbox. Used by send()
     * and by the watchdog after a lost worker. The caller holds the project lock.
     */
    private RenderMessages.Request dispatch(Project project, Job job) {
        VideoScript script = json.readValue(project.getScript(), VideoScript.class);
        Job assets = jobs.findFirstByProjectIdAndKindOrderByCreatedAtDesc(project.getId(), JobKind.ASSETS).orElseThrow();
        RenderMessages.Spec spec = specs.build(project, script, steps.findByJobIdOrderByTypeAscSceneIndexAsc(assets.getId()));

        UUID dispatchId = job.newDispatch(clock.instant());
        String key = outputKey(project.getId(), job.getId(), dispatchId);
        String uploadUrl = storage.presignPut(key, VIDEO_TYPE, RenderSpecBuilder.LINK_VALID);
        RenderMessages.Request req = new RenderMessages.Request(job.getId(), project.getId(), dispatchId,
                job.getAttempt(), renderTimeout(project).toSeconds(), key, uploadUrl, spec);
        outbox.enqueue(RenderQueues.REQUESTS, req);
        return req;
    }

    /**
     * Cancel (V2 A4): closes the project's active render job, so the watchdog leaves it alone and
     * every late result of its worker is refused. The caller holds the project lock.
     */
    public void cancelActive(UUID projectId) {
        jobs.findFirstByProjectIdAndKindOrderByCreatedAtDesc(projectId, JobKind.RENDER)
                .filter(job -> job.getStatus().isActive())
                .ifPresent(job -> {
                    job.moveTo(JobStatus.CANCELLED);
                    job.endDispatch();
                    JobStep step = stepOf(job.getId());
                    if (step.getStatus() == StepStatus.RUNNING) {
                        step.moveTo(StepStatus.PENDING);
                    }
                    if (step.getStatus().canMoveTo(StepStatus.SKIPPED)) {
                        step.moveTo(StepStatus.SKIPPED);
                    }
                    log.info("Render job {} of project {} cancelled", job.getId(), projectId);
                });
    }

    /** What the watchdog did with one job. */
    public enum WatchOutcome {
        NOTHING, QUEUE_TIMEOUT, RESENT_AFTER_LOST_WORKER, WORKER_LOST, RENDER_TIMEOUT, ORPHAN_CLOSED
    }

    /**
     * The watchdog's check of ONE running render job (V2 A4), with the project locked:
     *
     *   render timeout  claimed, and now > deadline + 1 min (even with heartbeats)  -> FAILED, no retry
     *   lost worker     claimed, lease expired more than 30 s ago -> a new dispatch once, then FAILED
     *   queue timeout   never claimed, 30 min after the send (no free worker)        -> FAILED, no retry
     *   orphan          the project is not RENDERING any more (cancelled earlier)    -> job CANCELLED
     *
     * Every FAILED refunds the credits, under the project lock, so a cancel at the same moment
     * cannot refund twice (refundVideo only gives back what is still charged).
     */
    public WatchOutcome watch(UUID jobId) {
        Watched watched = tx.execute(status -> {
            UUID projectId = jobs.findProjectIdById(jobId).orElse(null);
            if (projectId == null) {
                return null;
            }
            Project project = projects.lockById(projectId).orElseThrow();
            Job job = jobs.findById(jobId).orElseThrow();
            if (job.getKind() != JobKind.RENDER || job.getStatus() != JobStatus.RUNNING) {
                return new Watched(projectId, WatchOutcome.NOTHING);
            }
            return new Watched(projectId, decide(project, job, clock.instant()));
        });
        if (watched == null) {
            return WatchOutcome.NOTHING;
        }
        switch (watched.outcome()) {
            case NOTHING -> {
            }
            case RESENT_AFTER_LOST_WORKER, ORPHAN_CLOSED -> events.changed(watched.projectId());
            default -> events.renderFinished(watched.projectId());
        }
        return watched.outcome();
    }

    private record Watched(UUID projectId, WatchOutcome outcome) {
    }

    private WatchOutcome decide(Project project, Job job, Instant now) {
        if (project.getStatus() != ProjectStatus.RENDERING) {
            cancelActive(project.getId());
            return WatchOutcome.ORPHAN_CLOSED;
        }
        if (job.getDeadlineAt() != null && now.isAfter(job.getDeadlineAt().plus(deadlineGrace))) {
            failRender(project, job, "RENDER_TIMEOUT", "The render took longer than "
                    + renderTimeout(project).toMinutes() + " minutes and was stopped. Try again.");
            return WatchOutcome.RENDER_TIMEOUT;
        }
        if (job.getClaimedAt() == null) {
            // never claimed: wait for a free worker, but not forever (older jobs have no dispatched_at)
            Instant since = job.getDispatchedAt() != null ? job.getDispatchedAt() : job.getStartedAt();
            if (since != null && now.isAfter(since.plus(queueTimeout))) {
                failRender(project, job, "RENDER_QUEUE_TIMEOUT", "No render worker was free for "
                        + queueTimeout.toMinutes() + " minutes. Try again in a moment.");
                return WatchOutcome.QUEUE_TIMEOUT;
            }
            return WatchOutcome.NOTHING;
        }
        if (job.getLeaseUntil() != null && now.isAfter(job.getLeaseUntil().plus(leaseGrace))) {
            if (job.getAttempt() < maxAttempts) {
                RenderMessages.Request req = dispatch(project, job);
                log.warn("Render job {}: worker {} stopped answering; sent again as dispatch {} (attempt {})",
                        job.getId(), job.getLeaseOwner(), req.dispatchId(), req.attempt());
                return WatchOutcome.RESENT_AFTER_LOST_WORKER;
            }
            failRender(project, job, "RENDER_WORKER_LOST", "The render worker stopped answering "
                    + job.getAttempt() + " times. Try again.");
            return WatchOutcome.WORKER_LOST;
        }
        return WatchOutcome.NOTHING;
    }

    /**
     * Step + job + project FAILED and the credits back. The caller holds the project lock; a second
     * call refunds nothing (refundVideo only returns what is still charged).
     */
    private void failRender(Project project, Job job, String code, String message) {
        JobStep step = stepOf(job.getId());
        if (step.getStatus() == StepStatus.RUNNING) {
            step.fail(code, message, BigDecimal.ZERO);
        }
        job.fail(code, message);
        if (project.getStatus() == ProjectStatus.RENDERING) {
            project.fail(code, message);
            credits.refundVideo(project.getUserId(), project.getId(), "Video failed: " + code);
        }
        log.warn("Render job {} of project {} failed: {} - {}", job.getId(), project.getId(), code, message);
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
        UUID projectId = jobs.findProjectIdById(result.jobId()).orElse(null);
        if (projectId == null) {
            log.warn("Render result for unknown job {}", result.jobId());
            return;
        }
        switch (result.type()) {
            case RenderMessages.Result.CLAIMED, RenderMessages.Result.PROGRESS -> {
                if (Boolean.TRUE.equals(tx.execute(status -> holdLease(result)))
                        && RenderMessages.Result.PROGRESS.equals(result.type())) {
                    events.renderProgress(projectId, result.percent() == null ? 0 : result.percent());
                }
            }
            case RenderMessages.Result.SUCCEEDED -> {
                if (Boolean.TRUE.equals(tx.execute(status -> succeed(result)))) {
                    events.renderFinished(projectId);
                }
            }
            case RenderMessages.Result.FAILED -> {
                if (Boolean.TRUE.equals(tx.execute(status -> fail(result)))) {
                    events.renderFinished(projectId);
                }
            }
            default -> log.warn("Unknown render result type {}", result.type());
        }
    }

    /** The project row is locked, then the job is read fresh and fenced. Null = ignore the message. */
    private Fenced fenced(RenderMessages.Result r) {
        UUID projectId = jobs.findProjectIdById(r.jobId()).orElseThrow();
        Project project = projects.lockById(projectId).orElseThrow();
        Job job = jobs.findById(r.jobId()).orElseThrow();
        String worker = workerOf(r);
        Job.Fence fence = job.fence(r.dispatchId(), worker, clock.instant());
        if (fence != Job.Fence.ACCEPTED) {
            String detail = fence == Job.Fence.LEASE_HELD ? " (lease held by " + job.getLeaseOwner() + ")" : "";
            if (RenderMessages.Result.PROGRESS.equals(r.type())) {
                log.debug("Render {} of job {} ignored: {}{}", r.type(), job.getId(), fence, detail);
            } else {
                log.info("Render {} of job {} ignored: {}{} - dispatch {}, worker {}, current dispatch {}",
                        r.type(), job.getId(), fence, detail, r.dispatchId(), worker, job.getDispatchId());
            }
            return null;
        }
        return new Fenced(project, job, worker);
    }

    private record Fenced(Project project, Job job, String worker) {
    }

    /** CLAIMED or PROGRESS: the sender holds (or keeps) the lease. */
    private boolean holdLease(RenderMessages.Result r) {
        Fenced f = fenced(r);
        if (f == null) {
            return false;
        }
        boolean newOwner = !f.worker().equals(f.job().getLeaseOwner());
        f.job().holdLease(f.worker(), clock.instant(), lease, renderTimeout(f.project()));
        if (newOwner) {
            log.info("Render job {} claimed by {} (dispatch {}, attempt {}), deadline {}", f.job().getId(),
                    f.worker(), f.job().getDispatchId(), f.job().getAttempt(), f.job().getDeadlineAt());
        }
        return true;
    }

    /** max(10 min, 6 s per second of video) with the defaults: 10 min for 100 s. */
    Duration renderTimeout(Project project) {
        Duration scaled = renderTimeoutPerSecond.multipliedBy(Math.max(project.getDurationSeconds(), 1));
        return scaled.compareTo(renderTimeoutMin) > 0 ? scaled : renderTimeoutMin;
    }

    private boolean succeed(RenderMessages.Result r) {
        Fenced f = fenced(r);
        if (f == null) {
            return false;
        }
        Project project = f.project();
        Job job = f.job();
        JobStep step = stepOf(job.getId());
        if (step.getStatus() != StepStatus.RUNNING) {
            log.info("Render job {} already finished ({}); duplicate result ignored", job.getId(), step.getStatus());
            return false;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("contentType", VIDEO_TYPE);
        meta.put("durationSeconds", r.durationSeconds());
        meta.put("bytes", r.bytes());
        meta.put("renderSeconds", r.renderSeconds());
        meta.put("worker", f.worker());
        meta.put("attempt", job.getAttempt());
        step.succeed(outputKey(project.getId(), job.getId(), job.getDispatchId()),
                json.writeValueAsString(meta), BigDecimal.ZERO);
        job.moveTo(JobStatus.SUCCEEDED);
        if (project.getStatus() == ProjectStatus.RENDERING) {
            project.moveTo(ProjectStatus.DONE);
        }
        log.info("Render job {} done by {}: {} s video, rendered in {} s", job.getId(), f.worker(),
                r.durationSeconds(), r.renderSeconds());
        return true;
    }

    private boolean fail(RenderMessages.Result r) {
        Fenced f = fenced(r);
        if (f == null) {
            return false;
        }
        Project project = f.project();
        Job job = f.job();
        JobStep step = stepOf(job.getId());
        if (step.getStatus() != StepStatus.RUNNING) {
            return false;
        }
        String message = r.error() == null ? "The render worker failed" : r.error();
        // the worker stopped at its own time limit (V2 A4); any other code is not trusted
        String code = "RENDER_TIMEOUT".equals(r.errorCode()) ? "RENDER_TIMEOUT" : "RENDER_FAILED";
        failRender(project, job, code, message);
        return true;
    }

    static String outputKey(UUID projectId, UUID jobId, UUID dispatchId) {
        return "projects/" + projectId + "/" + jobId + "/" + dispatchId + "/video.mp4";
    }

    /** Lease owner name: the worker's id, or "unknown" for a worker that does not send one. */
    private static String workerOf(RenderMessages.Result r) {
        String id = r.workerId() == null || r.workerId().isBlank() ? "unknown" : r.workerId().trim();
        return id.length() > Job.LEASE_OWNER_LENGTH ? id.substring(0, Job.LEASE_OWNER_LENGTH) : id;
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
