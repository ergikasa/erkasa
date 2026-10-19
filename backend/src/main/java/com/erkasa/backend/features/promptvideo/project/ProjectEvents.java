package com.erkasa.backend.features.promptvideo.project;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.erkasa.backend.common.error.ApiException;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStepRepository;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;
import com.erkasa.backend.jobs.StepStatus;

import jakarta.annotation.PreDestroy;

/**
 * Live updates for the UI over Server-Sent Events: GET /api/projects/{id}/events.
 *
 * Every event is a SNAPSHOT of the project (status + progress), not a diff. If the browser
 * misses an event (network blip, reconnect), the next one is still correct on its own.
 *
 * - the first event is sent right after connecting, so the UI never waits for the first change
 * - one event per change: script ready/failed, every finished image or voice, ASSETS_READY...
 * - a comment line every 20 s keeps proxies (nginx, Cloudflare) from closing a quiet stream
 *
 * Kept in memory: fine for one backend instance (v1). With several instances (phase 9) the
 * changes must go through RabbitMQ / Postgres NOTIFY so every instance can tell its clients.
 */
@Component
public class ProjectEvents {

    private static final Logger log = LoggerFactory.getLogger(ProjectEvents.class);
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30);
    private static final int MAX_STREAMS_PER_PROJECT = 5;

    /** What every event carries. done/total count the latest ASSETS job (0/0 before approval). */
    public record Snapshot(UUID projectId, ProjectStatus status, String title, int done, int total,
                           String errorCode, String errorMessage, Instant at) {
    }

    private final ProjectRepository projects;
    private final JobRepository jobs;
    private final JobStepRepository steps;
    private final Map<UUID, List<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public ProjectEvents(ProjectRepository projects, JobRepository jobs, JobStepRepository steps) {
        this.projects = projects;
        this.jobs = jobs;
        this.steps = steps;
        heartbeat.scheduleAtFixedRate(this::ping, 20, 20, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
        streams.values().forEach(list -> list.forEach(SseEmitter::complete));
    }

    /** Opens a stream for a project the caller already owns (checked by the controller). */
    public SseEmitter subscribe(UUID projectId) {
        List<SseEmitter> list = streams.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>());
        if (list.size() >= MAX_STREAMS_PER_PROJECT) {
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_STREAMS",
                    "Too many live connections for this project; close another tab");
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        list.add(emitter);
        Runnable remove = () -> remove(projectId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());

        snapshot(projectId).ifPresent(s -> send(projectId, emitter, s));
        return emitter;
    }

    /**
     * Something about this project changed and is committed: tell everyone watching it.
     * Costs nothing when nobody is watching. Never throws: live updates must not break the pipeline.
     */
    public void changed(UUID projectId) {
        List<SseEmitter> list = streams.get(projectId);
        if (list == null || list.isEmpty()) {
            return;
        }
        try {
            snapshot(projectId).ifPresent(s -> list.forEach(e -> send(projectId, e, s)));
        } catch (RuntimeException e) {
            log.warn("Could not send live update for project {}", projectId, e);
        }
    }

    /** How many browsers are watching (for tests and monitoring). */
    public int watchers(UUID projectId) {
        List<SseEmitter> list = streams.get(projectId);
        return list == null ? 0 : list.size();
    }

    private java.util.Optional<Snapshot> snapshot(UUID projectId) {
        return projects.findById(projectId).map(this::snapshotOf);
    }

    private Snapshot snapshotOf(Project p) {
        int done = 0;
        int total = 0;
        var assets = jobs.findFirstByProjectIdAndKindOrderByCreatedAtDesc(p.getId(), JobKind.ASSETS);
        if (assets.isPresent()) {
            total = (int) steps.countByJobId(assets.get().getId());
            done = (int) steps.countByJobIdAndStatus(assets.get().getId(), StepStatus.SUCCEEDED);
        }
        return new Snapshot(p.getId(), p.getStatus(), p.getTitle(), done, total,
                p.getErrorCode(), p.getErrorMessage(), Instant.now());
    }

    private void send(UUID projectId, SseEmitter emitter, Snapshot s) {
        try {
            // SseEmitter is not thread-safe: pipeline threads and the heartbeat may send at once.
            synchronized (emitter) {
                emitter.send(SseEmitter.event()
                        .name("project")
                        .data(s, MediaType.APPLICATION_JSON));
            }
        } catch (IOException | IllegalStateException e) {
            // the browser went away
            remove(projectId, emitter);
        }
    }

    private void ping() {
        streams.forEach((projectId, list) -> list.forEach(emitter -> {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (IOException | IllegalStateException e) {
                remove(projectId, emitter);
            }
        }));
    }

    private void remove(UUID projectId, SseEmitter emitter) {
        List<SseEmitter> list = streams.get(projectId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                streams.remove(projectId, list);
            }
        }
    }
}
