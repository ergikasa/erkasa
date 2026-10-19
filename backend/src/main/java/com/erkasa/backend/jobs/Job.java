package com.erkasa.backend.jobs;

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
}