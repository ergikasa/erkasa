package com.erkasa.backend.features.promptvideo.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.ProjectStatus;

import tools.jackson.databind.JsonNode;

/** V2 task A3: lease, ownership and fencing of renders. Helpers: {@link AbstractRenderTest}. */
class RenderLeaseTests extends AbstractRenderTest {

    // ---------- tests ----------

    @Test
    void aClaimTakesTheLeaseAndHeartbeatsKeepIt() throws Exception {
        JsonNode req = rendering();
        Instant before = Instant.now();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);

        Job claimed = job(req);
        assertThat(claimed.getLeaseOwner()).isEqualTo("worker-a");
        assertThat(claimed.getClaimedAt()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(claimed.getLeaseUntil()).isAfter(claimed.getClaimedAt().plus(Duration.ofSeconds(110)));
        // 30 s video: max(10 min, 6 s x 30) = 10 min
        assertThat(Duration.between(claimed.getClaimedAt(), claimed.getDeadlineAt())).isEqualTo(Duration.ofMinutes(10));

        Thread.sleep(50);
        send(req, "worker-a", RenderMessages.Result.PROGRESS);
        Job beaten = job(req);
        assertThat(beaten.getLeaseUntil()).isAfter(claimed.getLeaseUntil());
        assertThat(beaten.getClaimedAt()).isEqualTo(claimed.getClaimedAt());
        assertThat(beaten.getDeadlineAt()).isEqualTo(claimed.getDeadlineAt());
    }

    @Test
    void aResultOfAnOldOrMissingDispatchIsIgnored() throws Exception {
        JsonNode req = rendering();

        send(req, UUID.randomUUID(), "worker-a", RenderMessages.Result.SUCCEEDED, null);
        send(req, null, "worker-a", RenderMessages.Result.SUCCEEDED, null);
        send(req, UUID.randomUUID(), "worker-a", RenderMessages.Result.FAILED, "stale failure");
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.RENDERING);
        assertThat(job(req).getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(refunds(req)).isZero();

        // the current dispatch still finishes it
        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.DONE);
        assertThat(jdbc.queryForObject("select output_key from job_steps where job_id = ? and type = 'RENDER'",
                String.class, jobId(req))).isEqualTo(req.path("outputKey").asString());
    }

    @Test
    void aSecondWorkerCannotClaimOrFinishWhileTheFirstHoldsTheLease() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);

        // RabbitMQ gave the same request to worker-b too
        send(req, "worker-b", RenderMessages.Result.CLAIMED);
        assertThat(job(req).getLeaseOwner()).isEqualTo("worker-a");
        send(req, "worker-b", RenderMessages.Result.PROGRESS);
        send(req, "worker-b", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.RENDERING);

        // only the owner's result counts
        send(req, "worker-a", RenderMessages.Result.FAILED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.FAILED);
        assertThat(projects.findById(UUID.fromString(req.path("projectId").asString())).orElseThrow()
                .getErrorMessage()).isEqualTo("failed on worker-a");
        assertThat(refunds(req)).isEqualTo(1);
    }

    @Test
    void aSecondFinalResultChangesNothing() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);
        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.DONE);

        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        send(req, "worker-a", RenderMessages.Result.FAILED);
        send(req, "worker-a", RenderMessages.Result.PROGRESS);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.DONE);
        assertThat(job(req).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(refunds(req)).isZero();
    }

    @Test
    void theDatabaseRefusesASecondActiveRenderForTheSameProject() throws Exception {
        JsonNode req = rendering();
        UUID projectId = UUID.fromString(req.path("projectId").asString());

        assertThatThrownBy(() -> jobs.saveAndFlush(Job.queue(projectId, JobKind.RENDER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jobs.countByProjectIdAndKind(projectId, JobKind.RENDER)).isEqualTo(1);
    }
}
