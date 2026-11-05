package com.erkasa.backend.features.promptvideo.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.Project;
import com.erkasa.backend.jobs.ProjectStatus;

import tools.jackson.databind.JsonNode;

/**
 * V2 task A4: the three render time limits, cancel during a render, and refunds that never happen
 * twice. The clock is moved forward instead of waiting; the scheduled watchdog is off in this
 * context, the tests run watchdog.check() / renders.watch() themselves.
 * Defaults: queue 30 min, lease 2 min + 30 s grace, render max(10 min, 6 s x 30 s) + 1 min grace.
 */
@TestPropertySource(properties = "erkasa.render.watchdog-enabled=false")
class RenderWatchdogTests extends AbstractRenderTest {

    private static final MovableClock CLOCK = new MovableClock();

    static Clock movableClock() {
        return CLOCK;
    }

    @TestBean(name = "clock", methodName = "movableClock")
    private Clock clock;

    @Autowired
    private RenderWatchdog watchdog;

    @AfterEach
    void resetClock() {
        CLOCK.reset();
    }

    private Project project(JsonNode req) {
        return projects.findById(UUID.fromString(projectId(req))).orElseThrow();
    }

    private void heartbeat(JsonNode req, String worker) {
        send(req, dispatchId(req), worker, RenderMessages.Result.PROGRESS, null);
    }

    // ---------- queue timeout ----------

    @Test
    void noWorkerFor30MinutesFailsTheRenderWithoutRetryAndRefunds() throws Exception {
        JsonNode req = rendering();
        assertThat(req.path("timeoutSeconds").asLong()).isEqualTo(600); // 30 s video: max(10 min, 3 min)

        CLOCK.advance(Duration.ofMinutes(29));
        watchdog.check();
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.RENDERING);

        CLOCK.advance(Duration.ofMinutes(2));
        watchdog.check();
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.FAILED);
        assertThat(project(req).getErrorCode()).isEqualTo("RENDER_QUEUE_TIMEOUT");
        assertThat(job(req).getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(refunds(req)).isEqualTo(1);
        assertThat(outboxRequests(req)).isEqualTo(1); // not sent again

        // a worker that shows up late cannot bring it back
        send(req, "worker-late", RenderMessages.Result.CLAIMED);
        send(req, "worker-late", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.FAILED);

        // "Try again" charges exactly once more
        CLOCK.reset();
        post("/api/projects/" + projectId(req) + "/retry", "", token);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.RENDERING);
        assertThat(credits(req, "VIDEO_CHARGE")).isEqualTo(2);
        assertThat(refunds(req)).isEqualTo(1);
        takeRequest(projectId(req)); // leave the queue clean
    }

    // ---------- lost worker ----------

    @Test
    void aLostWorkerIsReplacedOnceThenTheRenderFails() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);

        // lease (2 min) expired, but within the 30 s grace: another worker's heartbeat may still take it
        CLOCK.advance(Duration.ofMinutes(2).plusSeconds(10));
        watchdog.check();
        assertThat(job(req).getAttempt()).isEqualTo(1);
        assertThat(job(req).getDispatchId()).isEqualTo(dispatchId(req));

        CLOCK.advance(Duration.ofSeconds(30));
        assertThat(renders.watch(jobId(req))).isEqualTo(RenderJobService.WatchOutcome.RESENT_AFTER_LOST_WORKER);
        Job resent = job(req);
        assertThat(resent.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(resent.getAttempt()).isEqualTo(2);
        assertThat(resent.getDispatchId()).isNotEqualTo(dispatchId(req));
        assertThat(resent.getLeaseOwner()).isNull();
        assertThat(outboxRequests(req)).isEqualTo(2);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.RENDERING);
        assertThat(refunds(req)).isZero();

        JsonNode second = takeRequest(projectId(req));
        assertThat(second.path("dispatchId").asString()).isEqualTo(resent.getDispatchId().toString());
        assertThat(second.path("attempt").asInt()).isEqualTo(2);
        assertThat(second.path("outputKey").asString()).contains(resent.getDispatchId().toString());

        // the first worker comes back too late: its old dispatch is refused
        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.RENDERING);

        // the second worker takes it and disappears as well: no third try
        send(second, "worker-b", RenderMessages.Result.CLAIMED);
        CLOCK.advance(Duration.ofMinutes(2).plusSeconds(31));
        assertThat(renders.watch(jobId(req))).isEqualTo(RenderJobService.WatchOutcome.WORKER_LOST);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.FAILED);
        assertThat(project(req).getErrorCode()).isEqualTo("RENDER_WORKER_LOST");
        assertThat(refunds(req)).isEqualTo(1);
        assertThat(outboxRequests(req)).isEqualTo(2);
    }

    @Test
    void aSecondWorkersHeartbeatWinsOverTheWatchdogWithinTheGrace() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);

        // worker-a died; RabbitMQ gave the same request to worker-b, whose beat comes after a's lease expired
        CLOCK.advance(Duration.ofMinutes(2).plusSeconds(15));
        heartbeat(req, "worker-b");
        assertThat(job(req).getLeaseOwner()).isEqualTo("worker-b");

        watchdog.check();
        assertThat(job(req).getAttempt()).isEqualTo(1); // nothing sent again
        send(req, "worker-b", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.DONE);
    }

    // ---------- render timeout ----------

    @Test
    void aRenderThatTakesTooLongFailsEvenWithHeartbeats() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);

        for (int minute = 1; minute <= 10; minute++) {
            CLOCK.advance(Duration.ofMinutes(1));
            heartbeat(req, "worker-a");
            watchdog.check();
            assertThat(projectStatus(req)).as("minute %d", minute).isEqualTo(ProjectStatus.RENDERING);
        }
        // deadline (10 min) + 1 min grace
        CLOCK.advance(Duration.ofMinutes(1).plusSeconds(1));
        heartbeat(req, "worker-a");
        assertThat(renders.watch(jobId(req))).isEqualTo(RenderJobService.WatchOutcome.RENDER_TIMEOUT);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.FAILED);
        assertThat(project(req).getErrorCode()).isEqualTo("RENDER_TIMEOUT");
        assertThat(refunds(req)).isEqualTo(1);
        assertThat(outboxRequests(req)).isEqualTo(1); // no automatic retry

        // its late success changes nothing
        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.FAILED);
    }

    @Test
    void aWorkerThatStopsAtItsOwnLimitReportsRenderTimeout() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);
        send(req, dispatchId(req), "worker-a", RenderMessages.Result.FAILED,
                "Render stopped after 540 s (limit 540 s)", "RENDER_TIMEOUT");

        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.FAILED);
        assertThat(project(req).getErrorCode()).isEqualTo("RENDER_TIMEOUT");
        assertThat(refunds(req)).isEqualTo(1);
    }

    @Test
    void anUnknownErrorCodeFromTheWorkerIsAPlainRenderFailure() throws Exception {
        JsonNode req = rendering();
        send(req, dispatchId(req), "worker-a", RenderMessages.Result.FAILED, "boom", "CREDITS_PLEASE");
        assertThat(project(req).getErrorCode()).isEqualTo("RENDER_FAILED");
    }

    // ---------- cancel ----------

    @Test
    void cancelDuringTheRenderClosesTheJobAndLateResultsAreIgnored() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);

        post("/api/projects/" + projectId(req) + "/cancel", "", token);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.CANCELLED);
        Job job = job(req);
        assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.getDispatchId()).isNull();
        assertThat(jdbc.queryForObject("select status from job_steps where job_id = ? and type = 'RENDER'",
                String.class, jobId(req))).isEqualTo("SKIPPED");
        assertThat(refunds(req)).isEqualTo(1);

        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.CANCELLED);
        assertThat(call("GET", "/api/projects/" + projectId(req), token).path("status").asString()).isEqualTo("CANCELLED");

        // the watchdog leaves it alone, even long after
        CLOCK.advance(Duration.ofMinutes(40));
        assertThat(renders.watch(jobId(req))).isEqualTo(RenderJobService.WatchOutcome.NOTHING);
        assertThat(job(req).getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(refunds(req)).isEqualTo(1);
    }

    @Test
    void cancelAndTheWatchdogAtTheSameMomentRefundExactlyOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            JsonNode req = rendering();
            String owner = token;
            int before = call("GET", "/api/credits", owner).path("balance").asInt();
            CLOCK.advance(Duration.ofMinutes(31)); // the queue timeout is due

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<?> cancel = pool.submit(() -> {
                    go.await();
                    return post("/api/projects/" + projectId(req) + "/cancel", "", owner);
                });
                Future<?> watch = pool.submit(() -> {
                    go.await();
                    return renders.watch(jobId(req));
                });
                go.countDown();
                cancel.get(30, TimeUnit.SECONDS);
                watch.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                CLOCK.reset();
            }

            assertThat(projectStatus(req)).as("round %d", round).isEqualTo(ProjectStatus.CANCELLED);
            assertThat(refunds(req)).as("round %d", round).isEqualTo(1);
            // exactly the 30 credits charged at approve came back once
            assertThat(call("GET", "/api/credits", owner).path("balance").asInt()).isEqualTo(before + 30);
        }
    }

    // ---------- orphans ----------

    @Test
    void aRunningRenderOfAProjectThatIsNoLongerRenderingIsClosed() throws Exception {
        JsonNode req = rendering();
        // the state an old cancel (before A4) left behind: project CANCELLED, render job still RUNNING
        jdbc.update("update projects set status = 'CANCELLED' where id = ?::uuid", projectId(req));

        assertThat(renders.watch(jobId(req))).isEqualTo(RenderJobService.WatchOutcome.ORPHAN_CLOSED);
        assertThat(job(req).getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(refunds(req)).isZero(); // credits are not the watchdog's business here
    }
}
