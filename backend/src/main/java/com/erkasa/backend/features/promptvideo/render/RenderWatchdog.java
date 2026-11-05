package com.erkasa.backend.features.promptvideo.render;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;

/**
 * Every 15 seconds: checks every RUNNING render job against the three time limits (V2 task A4).
 * The rules are in RenderJobService.watch(); this class only finds the jobs and schedules the check.
 *
 *   queue timeout    30 min without CLAIMED          -> FAILED RENDER_QUEUE_TIMEOUT (+ refund)
 *   lost worker      lease (2 min) expired + 30 s    -> sent again once, then FAILED RENDER_WORKER_LOST
 *   render timeout   max(10 min, 6 s x video seconds) after the claim + 1 min -> FAILED RENDER_TIMEOUT
 *
 * Only the watchdog expires leases. It is safe with several backend instances: every check runs
 * with the project row locked and works on the fresh state.
 *
 * erkasa.render.watchdog-enabled=false stops the schedule (tests call check() by hand).
 */
@Component
public class RenderWatchdog {

    private static final Logger log = LoggerFactory.getLogger(RenderWatchdog.class);

    private final JobRepository jobs;
    private final RenderJobService renders;
    private final boolean enabled;

    public RenderWatchdog(JobRepository jobs, RenderJobService renders,
                          @Value("${erkasa.render.watchdog-enabled:true}") boolean enabled) {
        this.jobs = jobs;
        this.renders = renders;
        this.enabled = enabled;
    }

    @Scheduled(initialDelayString = "${erkasa.render.watchdog-delay-ms:15000}",
            fixedDelayString = "${erkasa.render.watchdog-delay-ms:15000}")
    void scheduledCheck() {
        if (!enabled) {
            return;
        }
        try {
            check();
        } catch (RuntimeException e) {
            log.warn("Render watchdog run failed: {}", e.getMessage());
        }
    }

    /** One run over every running render. @return how many jobs got each outcome (NOTHING excluded) */
    public Map<RenderJobService.WatchOutcome, Integer> check() {
        Map<RenderJobService.WatchOutcome, Integer> done = new EnumMap<>(RenderJobService.WatchOutcome.class);
        for (Job job : jobs.findByKindAndStatus(JobKind.RENDER, JobStatus.RUNNING)) {
            try {
                RenderJobService.WatchOutcome outcome = renders.watch(job.getId());
                if (outcome != RenderJobService.WatchOutcome.NOTHING) {
                    done.merge(outcome, 1, Integer::sum);
                }
            } catch (RuntimeException e) {
                // one broken job must not stop the check of the others
                log.error("Render watchdog could not check job {}", job.getId(), e);
            }
        }
        if (!done.isEmpty()) {
            log.info("Render watchdog: {}", done);
        }
        return done;
    }
}
