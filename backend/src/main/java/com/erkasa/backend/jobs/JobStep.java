package com.erkasa.backend.jobs;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * One row of job_steps (see V8__projects_jobs.sql): the script, or one scene's image or voice.
 * (job_id, type, scene_index) is unique, so the same scene can never be generated twice in one job.
 */
@Entity
@Table(name = "job_steps")
public class JobStep {

    /** scene_index for steps that belong to the whole video (SCRIPT, RENDER). */
    public static final int WHOLE_VIDEO = -1;

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StepType type;

    @Column(name = "scene_index", nullable = false)
    private int sceneIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StepStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "output_key", length = 500)
    private String outputKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_meta", columnDefinition = "jsonb")
    private String outputMeta;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costUsd;

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

    protected JobStep() {
    }

    /** A step for the whole video (SCRIPT, RENDER). */
    public static JobStep forVideo(UUID jobId, StepType type) {
        if (type.perScene()) {
            throw new IllegalArgumentException(type + " needs a scene index");
        }
        return create(jobId, type, WHOLE_VIDEO);
    }

    /** A step for one scene (IMAGE, VOICE). Scenes are counted from 0. */
    public static JobStep forScene(UUID jobId, StepType type, int sceneIndex) {
        if (!type.perScene()) {
            throw new IllegalArgumentException(type + " is not a per-scene step");
        }
        if (sceneIndex < 0) {
            throw new IllegalArgumentException("Scene index must be 0 or more");
        }
        return create(jobId, type, sceneIndex);
    }

    private static JobStep create(UUID jobId, StepType type, int sceneIndex) {
        JobStep step = new JobStep();
        step.id = UUID.randomUUID();
        step.jobId = jobId;
        step.type = type;
        step.sceneIndex = sceneIndex;
        step.status = StepStatus.PENDING;
        step.attempts = 0;
        step.costUsd = BigDecimal.ZERO;
        return step;
    }

    public void moveTo(StepStatus target) {
        if (!status.canMoveTo(target)) {
            throw new InvalidTransitionException("step " + type + "#" + sceneIndex, status, target);
        }
        status = target;
        Instant now = Instant.now();
        switch (target) {
            case RUNNING -> {
                attempts++;
                startedAt = now;
                finishedAt = null;
                errorCode = null;
                errorMessage = null;
            }
            case SUCCEEDED, FAILED, SKIPPED -> finishedAt = now;
            case PENDING -> finishedAt = null;
        }
    }

    /** The step produced a file. cost is added, so a retried step keeps the cost of failed tries too. */
    public void succeed(String outputKey, String outputMetaJson, BigDecimal cost) {
        moveTo(StepStatus.SUCCEEDED);
        this.outputKey = outputKey;
        this.outputMeta = outputMetaJson;
        addCost(cost);
    }

    public void fail(String code, String message, BigDecimal cost) {
        moveTo(StepStatus.FAILED);
        this.errorCode = code;
        this.errorMessage = Text.shorten(message, 500);
        addCost(cost);
    }

    private void addCost(BigDecimal cost) {
        if (cost != null) {
            costUsd = costUsd.add(cost);
        }
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

    public UUID getJobId() {
        return jobId;
    }

    public StepType getType() {
        return type;
    }

    public int getSceneIndex() {
        return sceneIndex;
    }

    public StepStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getOutputKey() {
        return outputKey;
    }

    public String getOutputMeta() {
        return outputMeta;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
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
}