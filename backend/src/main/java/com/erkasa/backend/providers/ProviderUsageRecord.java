package com.erkasa.backend.providers;



import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** One row of provider_usage (see V6__provider_usage.sql). Written once, never changed. */
@Entity
@Table(name = "provider_usage")
public class ProviderUsageRecord {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProviderKind kind;

    @Column(nullable = false, length = 50)
    private String provider;

    @Column(length = 100)
    private String model;

    @Column(name = "unit_type", length = 20)
    private String unitType;

    @Column(name = "input_units", nullable = false)
    private long inputUnits;

    @Column(name = "output_units", nullable = false)
    private long outputUnits;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProviderUsageRecord() {
    }

    public static ProviderUsageRecord success(ProviderKind kind, String provider, ProviderUsage usage,
                                              long durationMs, CallContext ctx) {
        ProviderUsageRecord r = base(kind, provider, usage.model(), durationMs, ctx);
        r.unitType = usage.unitType();
        r.inputUnits = usage.inputUnits();
        r.outputUnits = usage.outputUnits();
        r.costUsd = usage.costUsd() == null ? BigDecimal.ZERO : usage.costUsd();
        r.success = true;
        return r;
    }

    public static ProviderUsageRecord failure(ProviderKind kind, String provider, String model,
                                              String errorCode, long durationMs, CallContext ctx) {
        ProviderUsageRecord r = base(kind, provider, model, durationMs, ctx);
        r.costUsd = BigDecimal.ZERO;
        r.success = false;
        r.errorCode = errorCode;
        return r;
    }

    private static ProviderUsageRecord base(ProviderKind kind, String provider, String model,
                                            long durationMs, CallContext ctx) {
        ProviderUsageRecord r = new ProviderUsageRecord();
        r.id = UUID.randomUUID();
        r.kind = kind;
        r.provider = provider;
        r.model = model;
        r.durationMs = (int) Math.min(durationMs, Integer.MAX_VALUE);
        r.userId = ctx == null ? null : ctx.userId();
        r.jobId = ctx == null ? null : ctx.jobId();
        return r;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public ProviderKind getKind() {
        return kind;
    }

    public String getProvider() {
        return provider;
    }

    public boolean isSuccess() {
        return success;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }
}