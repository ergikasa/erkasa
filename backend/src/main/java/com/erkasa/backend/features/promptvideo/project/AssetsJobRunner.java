package com.erkasa.backend.features.promptvideo.project;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.erkasa.backend.credits.CreditService;
import com.erkasa.backend.assets.AssetStorage;
import com.erkasa.backend.features.promptvideo.script.VideoScript;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.JobStep;
import com.erkasa.backend.jobs.JobStepRepository;
import com.erkasa.backend.jobs.PipelineWorkers;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;
import com.erkasa.backend.jobs.StepStatus;
import com.erkasa.backend.jobs.StepType;
import com.erkasa.backend.providers.CallContext;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderGateway;
import com.erkasa.backend.providers.image.GeneratedImage;
import com.erkasa.backend.providers.image.ImageRequest;
import com.erkasa.backend.providers.tts.SpeechRequest;
import com.erkasa.backend.providers.tts.SpeechResult;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs an ASSETS job: one IMAGE and one VOICE step per scene, several at the same time.
 *
 *   1. (tx) job RUNNING
 *   2. every PENDING step in parallel (at most erkasa.pipeline.parallel-calls at once):
 *        (tx) step RUNNING -> call provider -> upload file to storage -> (tx) step SUCCEEDED
 *        or step FAILED with the reason. One failed scene does not stop the others.
 *   3. (tx) all SUCCEEDED -> job SUCCEEDED, project ASSETS_READY
 *           otherwise     -> job FAILED, project FAILED ("2 of 16 steps failed")
 *
 * Steps already SUCCEEDED are never run again, so a retry (4.4) only pays for what failed.
 * If the user cancels meanwhile, steps not started yet are SKIPPED.
 */
@Service
public class AssetsJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AssetsJobRunner.class);

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final ProviderGateway gateway;
    private final AssetStorage storage;
    private final PipelineWorkers workers;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ProjectEvents events;
    private final CreditService credits;
    /** Provider calls of all jobs share this pool, so 10 users approving at once cannot open 300 connections. */
    private final ExecutorService calls;

    public AssetsJobRunner(ProjectRepository projects, JobRepository jobs, JobStepRepository steps,
                           ProviderGateway gateway, AssetStorage storage, PipelineWorkers workers,
                           TransactionTemplate tx, ObjectMapper json, ProjectEvents events, CreditService credits,
                           @Value("${erkasa.pipeline.parallel-calls:6}") int parallelCalls) {
        this.projects = projects;
        this.jobs = jobs;
        this.steps = steps;
        this.gateway = gateway;
        this.storage = storage;
        this.workers = workers;
        this.tx = tx;
        this.json = json;
        this.events = events;
        this.credits = credits;
        AtomicInteger n = new AtomicInteger();
        this.calls = Executors.newFixedThreadPool(parallelCalls, r -> {
            Thread t = new Thread(r, "provider-call-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        calls.shutdownNow();
    }

    /** Call AFTER the transaction that created the job and its steps has committed. */
    public void start(UUID jobId) {
        workers.submit("assets job " + jobId, () -> run(jobId));
    }

    /** What the parallel steps need to know, read once at the start. */
    private record Context(UUID projectId, UUID jobId, CallContext calls, VideoScript script,
                           com.erkasa.backend.providers.AspectRatio aspectRatio,
                           com.erkasa.backend.providers.Language language, String voiceId) {
    }

    void run(UUID jobId) {
        Context ctx = tx.execute(status -> begin(jobId));
        if (ctx == null) {
            return;
        }
        List<JobStep> todo = steps.findByJobIdAndStatusInOrderByTypeAscSceneIndexAsc(jobId, List.of(StepStatus.PENDING));
        log.info("Assets job {}: {} steps to run", jobId, todo.size());

        List<CompletableFuture<Void>> running = new ArrayList<>();
        for (JobStep step : todo) {
            running.add(CompletableFuture.runAsync(() -> runStep(ctx, step.getId()), calls));
        }
        CompletableFuture.allOf(running.toArray(CompletableFuture[]::new)).join();

        tx.executeWithoutResult(status -> finish(jobId));
        events.changed(ctx.projectId());
    }

    private Context begin(UUID jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        if (job.getStatus() != JobStatus.QUEUED) {
            return null;
        }
        Project project = projects.findById(job.getProjectId()).orElseThrow();
        if (project.getStatus() != ProjectStatus.ASSETS_PENDING) {
            job.moveTo(JobStatus.CANCELLED);
            skipPending(jobId);
            return null;
        }
        VideoScript script = json.readValue(project.getScript(), VideoScript.class);
        job.moveTo(JobStatus.RUNNING);
        return new Context(project.getId(), jobId, new CallContext(project.getUserId(), jobId), script,
                project.getAspectRatio(), project.getLanguage(), project.getVoiceId());
    }

    /** One scene's image or voice. Never throws: the result is written on the step. */
    private void runStep(Context ctx, UUID stepId) {
        JobStep step = tx.execute(status -> {
            JobStep s = steps.findById(stepId).orElseThrow();
            ProjectStatus now = projects.findById(ctx.projectId()).orElseThrow().getStatus();
            if (s.getStatus() != StepStatus.PENDING || now != ProjectStatus.ASSETS_PENDING) {
                if (s.getStatus() == StepStatus.PENDING) {
                    s.moveTo(StepStatus.SKIPPED); // cancelled while waiting
                }
                return null;
            }
            s.moveTo(StepStatus.RUNNING);
            return s;
        });
        if (step == null) {
            return;
        }

        int scene = step.getSceneIndex();
        try {
            Done done = step.getType() == StepType.IMAGE ? image(ctx, scene) : voice(ctx, scene);
            tx.executeWithoutResult(status ->
                    steps.findById(stepId).orElseThrow().succeed(done.key(), done.metaJson(), done.cost()));
        } catch (ProviderException e) {
            log.warn("Assets job {}: {} scene {} failed: {}", ctx.jobId(), step.getType(), scene, e.getMessage());
            tx.executeWithoutResult(status ->
                    steps.findById(stepId).orElseThrow().fail(e.getCode(), e.getMessage(), BigDecimal.ZERO));
        } catch (RuntimeException e) {
            log.error("Assets job {}: {} scene {} crashed", ctx.jobId(), step.getType(), scene, e);
            tx.executeWithoutResult(status ->
                    steps.findById(stepId).orElseThrow().fail("INTERNAL_ERROR", String.valueOf(e.getMessage()), BigDecimal.ZERO));
        }
        events.changed(ctx.projectId()); // progress: one more file done (or failed)
    }

    private record Done(String key, String metaJson, BigDecimal cost) {
    }

    private Done image(Context ctx, int scene) {
        VideoScript.Scene s = ctx.script().scenes().get(scene);
        GeneratedImage image = gateway.generateImage(new ImageRequest(s.imagePrompt(), ctx.aspectRatio()), ctx.calls());
        String key = key(ctx, "scene-" + scene + "." + AssetStorage.extension(image.contentType()));
        storage.put(key, image.data(), image.contentType());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("contentType", image.contentType());
        meta.put("width", image.width());
        meta.put("height", image.height());
        meta.put("bytes", image.data().length);
        return new Done(key, json.writeValueAsString(meta), image.usage().costUsd());
    }

    private Done voice(Context ctx, int scene) {
        VideoScript.Scene s = ctx.script().scenes().get(scene);
        SpeechResult voice = gateway.synthesize(new SpeechRequest(s.narration(), ctx.language(), ctx.voiceId()), ctx.calls());
        String key = key(ctx, "voice-" + scene + "." + AssetStorage.extension(voice.contentType()));
        storage.put(key, voice.audio(), voice.contentType());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("contentType", voice.contentType());
        meta.put("durationSeconds", voice.durationSeconds());
        meta.put("bytes", voice.audio().length);
        // Word timings: the render worker (phase 5) builds the subtitles from these.
        meta.put("words", voice.words());
        return new Done(key, json.writeValueAsString(meta), voice.usage().costUsd());
    }

    private static String key(Context ctx, String file) {
        return "projects/" + ctx.projectId() + "/" + ctx.jobId() + "/" + file;
    }

    private void finish(UUID jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        Project project = projects.lockById(job.getProjectId()).orElseThrow();
        List<JobStep> all = steps.findByJobIdOrderByTypeAscSceneIndexAsc(jobId);

        if (project.getStatus() != ProjectStatus.ASSETS_PENDING) {
            // cancelled while we were working
            skipPending(jobId);
            job.moveTo(JobStatus.CANCELLED);
            log.info("Assets job {} stopped: project is {}", jobId, project.getStatus());
            return;
        }

        List<JobStep> failed = all.stream().filter(s -> s.getStatus() == StepStatus.FAILED).toList();
        if (failed.isEmpty()) {
            job.moveTo(JobStatus.SUCCEEDED);
            project.moveTo(ProjectStatus.ASSETS_READY);
            log.info("Assets job {} done: {} files", jobId, all.size());
            return;
        }

        String which = failed.stream()
                .map(s -> s.getType() + " scene " + (s.getSceneIndex() + 1) + " (" + s.getErrorCode() + ")")
                .reduce((a, b) -> a + ", " + b).orElse("");
        String message = failed.size() + " of " + all.size() + " steps failed: " + which;
        // The first failed step's code goes on the project; the message lists all of them.
        String code = failed.get(0).getErrorCode();
        job.fail(code, message);
        project.fail(code, message);
        credits.refundVideo(project.getUserId(), project.getId(), "Video failed: " + code);
        log.warn("Assets job {} failed: {}", jobId, message);
    }

    private void skipPending(UUID jobId) {
        for (JobStep s : steps.findByJobIdAndStatusInOrderByTypeAscSceneIndexAsc(jobId, List.of(StepStatus.PENDING))) {
            s.moveTo(StepStatus.SKIPPED);
        }
    }
}
