package com.erkasa.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** V2 task A3: dispatch, lease and fencing rules of a RENDER job (no Spring, no Docker). */
class JobLeaseTests {

    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration RENDER_TIMEOUT = Duration.ofMinutes(10);

    private static Job running() {
        Job job = Job.queue(UUID.randomUUID(), JobKind.RENDER);
        job.moveTo(JobStatus.RUNNING);
        return job;
    }

    @Test
    void everyDispatchIsNewAndCountsTheAttempt() {
        Job job = running();
        assertThat(job.getAttempt()).isEqualTo(1);
        UUID first = job.newDispatch(T0);
        assertThat(job.getAttempt()).isEqualTo(1);
        assertThat(job.getDispatchedAt()).isEqualTo(T0);

        job.holdLease("worker-a", T0.plusSeconds(5), LEASE, RENDER_TIMEOUT);
        UUID second = job.newDispatch(T0.plusSeconds(300));
        assertThat(second).isNotEqualTo(first);
        assertThat(job.getAttempt()).isEqualTo(2);
        // a new dispatch starts without a lease
        assertThat(job.getLeaseOwner()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getClaimedAt()).isNull();
        assertThat(job.getDeadlineAt()).isNull();
    }

    @Test
    void onlyTheCurrentDispatchOfARunningJobIsAccepted() {
        Job job = running();
        UUID old = job.newDispatch(T0);
        UUID current = job.newDispatch(T0.plusSeconds(1));

        assertThat(job.fence(current, "worker-a", T0)).isEqualTo(Job.Fence.ACCEPTED);
        assertThat(job.fence(old, "worker-a", T0)).isEqualTo(Job.Fence.STALE_DISPATCH);
        assertThat(job.fence(null, "worker-a", T0)).isEqualTo(Job.Fence.STALE_DISPATCH);

        job.moveTo(JobStatus.SUCCEEDED);
        assertThat(job.fence(current, "worker-a", T0)).isEqualTo(Job.Fence.NOT_RUNNING);
    }

    @Test
    void aClaimStartsTheLeaseAndTheDeadline() {
        Job job = running();
        UUID d = job.newDispatch(T0);
        Instant claim = T0.plusSeconds(3);
        job.holdLease("worker-a", claim, LEASE, RENDER_TIMEOUT);

        assertThat(job.getLeaseOwner()).isEqualTo("worker-a");
        assertThat(job.getClaimedAt()).isEqualTo(claim);
        assertThat(job.getLeaseUntil()).isEqualTo(claim.plus(LEASE));
        assertThat(job.getDeadlineAt()).isEqualTo(claim.plus(RENDER_TIMEOUT));
        assertThat(job.fence(d, "worker-a", claim)).isEqualTo(Job.Fence.ACCEPTED);
    }

    @Test
    void aHeartbeatMovesTheLeaseButNotTheDeadline() {
        Job job = running();
        job.newDispatch(T0);
        job.holdLease("worker-a", T0, LEASE, RENDER_TIMEOUT);
        Instant beat = T0.plusSeconds(20);
        job.holdLease("worker-a", beat, LEASE, RENDER_TIMEOUT);

        assertThat(job.getHeartbeatAt()).isEqualTo(beat);
        assertThat(job.getLeaseUntil()).isEqualTo(beat.plus(LEASE));
        assertThat(job.getClaimedAt()).isEqualTo(T0);
        assertThat(job.getDeadlineAt()).isEqualTo(T0.plus(RENDER_TIMEOUT));
    }

    @Test
    void anotherWorkerIsRefusedWhileTheLeaseHoldsAndMayTakeOverAfter() {
        Job job = running();
        UUID d = job.newDispatch(T0);
        job.holdLease("worker-a", T0, LEASE, RENDER_TIMEOUT);

        // the same dispatch reached worker-b (RabbitMQ redelivered it): refused while a's lease holds
        assertThat(job.fence(d, "worker-b", T0.plusSeconds(60))).isEqualTo(Job.Fence.LEASE_HELD);

        // a stopped beating: once the lease expired, b's heartbeat takes it over, with a new deadline
        Instant later = T0.plus(LEASE).plusSeconds(1);
        assertThat(job.fence(d, "worker-b", later)).isEqualTo(Job.Fence.ACCEPTED);
        job.holdLease("worker-b", later, LEASE, RENDER_TIMEOUT);
        assertThat(job.getLeaseOwner()).isEqualTo("worker-b");
        assertThat(job.getClaimedAt()).isEqualTo(later);
        assertThat(job.getDeadlineAt()).isEqualTo(later.plus(RENDER_TIMEOUT));

        // and now a is the one refused
        assertThat(job.fence(d, "worker-a", later.plusSeconds(1))).isEqualTo(Job.Fence.LEASE_HELD);
    }
}
