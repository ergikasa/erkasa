package com.erkasa.backend.credits;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** One change of a user's credits (see V9__credits.sql). Never updated or deleted. */
@Entity
@Table(name = "credit_transactions")
public class CreditTransaction {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(nullable = false, updatable = false)
    private int amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private int balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private CreditReason reason;

    @Column(length = 200, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreditTransaction() {
    }

    static CreditTransaction of(UUID userId, UUID projectId, int amount, int balanceAfter, CreditReason reason, String note) {
        CreditTransaction t = new CreditTransaction();
        t.id = UUID.randomUUID();
        t.userId = userId;
        t.projectId = projectId;
        t.amount = amount;
        t.balanceAfter = balanceAfter;
        t.reason = reason;
        t.note = note == null ? null : (note.length() > 200 ? note.substring(0, 200) : note);
        return t;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getAmount() {
        return amount;
    }

    public int getBalanceAfter() {
        return balanceAfter;
    }

    public CreditReason getReason() {
        return reason;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
