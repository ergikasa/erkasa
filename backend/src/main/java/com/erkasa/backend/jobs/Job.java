package com.erkasa.backend.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * One row of jobs (see V8__projects_jobs.sql): one run of one stage of a project.
 * Its id is also the job_id in provider_usage, so every cent spent is linked to it.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private JobKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private JobStatus status;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ---------- render dispatch and lease (V13, V2 task A3); only RENDER jobs use them ----------

    /** 1 for the first send to the worker, +1 for every new dispatch after that. */
    @Column(nullable = false)
    private int attempt = 1;

    /** The current dispatch: results with another id are stale (fencing). */
    @Column(name = "dispatch_id")
    private UUID dispatchId;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    /** When the current lease owner started (its CLAIMED, or its first accepted heartbeat). */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "lease_owner", length = LEASE_OWNER_LENGTH)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

    /** claimed_at + the render timeout: the watchdog (A4) fails the render after this. */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    protected Job() {
    }

    public static Job queue(UUID projectId, JobKind kind) {
        Job job = new Job();
        job.id = UUID.randomUUID();
        job.projectId = projectId;
        job.kind = kind;
        job.status = JobStatus.QUEUED;
        return job;
    }

    public void moveTo(JobStatus target) {
        if (!status.canMoveTo(target)) {
            throw new InvalidTransitionException("job " + id, status, target);
        }
        status = target;
        Instant now = Instant.now();
        switch (target) {
            case RUNNING -> {
                startedAt = now;
                finishedAt = null;
                errorCode = null;
                errorMessage = null;
            }
            case SUCCEEDED, FAILED, CANCELLED -> finishedAt = now;
            case QUEUED -> {
                startedAt = null;
                finishedAt = null;
            }
        }
    }

    public static final int LEASE_OWNER_LENGTH = 100;

    /** Whether a message from a worker may change this job (fencing, V2 task A3). */
    public enum Fence {
        ACCEPTED,
        /** the job is not RUNNING any more (finished, failed, cancelled, or waiting to be sent) */
        NOT_RUNNING,
        /** the message belongs to an older (or unknown) dispatch of this job */
        STALE_DISPATCH,
        /** another worker holds a lease on this dispatch that has not expired */
        LEASE_HELD
    }

    /**
     * A new send to the worker: a new dispatch id, attempt + 1 if it was sent before, no lease.
     * Results of earlier dispatches are refused from now on.
     */
    public UUID newDispatch(Instant now) {
        if (dispatchedAt != null) {
            attempt++;
        }
        dispatchId = UUID.randomUUID();
        dispatchedAt = now;
        claimedAt = null;
        leaseOwner = null;
        leaseUntil = null;
        heartbeatAt = null;
        deadlineAt = null;
        return dispatchId;
    }

    /**
     * May a message of {@code worker} for {@code dispatch} change this job? Yes only if the job is
     * RUNNING, the dispatch is the current one, and no OTHER worker holds an unexpired lease.
     * (Two workers can get the same dispatch when RabbitMQ redelivers a request; the first one to
     * claim it wins, the other is refused until the first one's lease expires.)
     */
    public Fence fence(UUID dispatch, String worker, Instant now) {
        if (status != JobStatus.RUNNING) {
            return Fence.NOT_RUNNING;
        }
        if (dispatch == null || !dispatch.equals(dispatchId)) {
            return Fence.STALE_DISPATCH;
        }
        if (leaseOwner != null && !leaseOwner.equals(worker) && leaseUntil != null && leaseUntil.isAfter(now)) {
            return Fence.LEASE_HELD;
        }
        return Fence.ACCEPTED;
    }

    /**
     * After an accepted CLAIMED or PROGRESS: {@code worker} holds the lease until now + lease.
     * A new owner (first claim, or taking over an expired lease) also starts the render deadline.
     */
    public void holdLease(String worker, Instant now, Duration lease, Duration renderTimeout) {
        if (leaseOwner == null || !leaseOwner.equals(worker)) {
            leaseOwner = worker;
            claimedAt = now;
            deadlineAt = now.plus(renderTimeout);
        }
        heartbeatAt = now;
        leaseUntil = now.plus(lease);
    }

    public void fail(String code, String message) {
        moveTo(JobStatus.FAILED);
        errorCode = code;
        errorMessage = Text.shorten(message, 500);
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public JobKind getKind() {
        return kind;
    }

    public JobStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getAttempt() {
        return attempt;
    }

    public UUID getDispatchId() {
        return dispatchId;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }
}