package com.erkasa.backend.billing;

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

/** One Paddle transaction that bought credits (see V10__payments.sql). */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "paddle_transaction_id", nullable = false, updatable = false, length = 64)
    private String paddleTransactionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 30)
    private String pack;

    @Column(nullable = false, updatable = false)
    private int credits;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "credits_taken_back", nullable = false)
    private int creditsTakenBack;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    static Payment completed(String paddleTransactionId, UUID userId, String pack, int credits, long amountMinor, String currency) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.paddleTransactionId = paddleTransactionId;
        p.userId = userId;
        p.pack = pack;
        p.credits = credits;
        p.amountMinor = amountMinor;
        p.currency = currency == null ? "USD" : currency;
        p.status = PaymentStatus.COMPLETED;
        return p;
    }

    void refunded(int takenBack) {
        status = PaymentStatus.REFUNDED;
        creditsTakenBack = takenBack;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getPaddleTransactionId() {
        return paddleTransactionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPack() {
        return pack;
    }

    public int getCredits() {
        return credits;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public int getCreditsTakenBack() {
        return creditsTakenBack;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
