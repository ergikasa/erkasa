package com.erkasa.backend.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One row of outbox_messages (see V12__outbox.sql): a message for RabbitMQ that was written in the
 * same transaction as the change that caused it, and is published by {@link OutboxRelay}.
 */
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage {

    /** Longest wait between two failed attempts. */
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    static final int MAX_ERROR_LENGTH = 500;

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String queue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    protected OutboxMessage() {
    }

    static OutboxMessage create(String queue, String payload) {
        OutboxMessage m = new OutboxMessage();
        m.id = UUID.randomUUID();
        m.queue = queue;
        m.payload = payload;
        return m;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        nextAttemptAt = now;
    }

    /** RabbitMQ confirmed the message. */
    void markSent(Instant now) {
        sentAt = now;
        attempts++;
        lastError = null;
    }

    /** Publishing failed: try again later, waiting 2, 4, 8 ... up to 60 seconds. */
    void markFailed(String error, Instant now) {
        attempts++;
        lastError = error == null ? "unknown error"
                : error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        nextAttemptAt = now.plus(backoff(attempts));
    }

    static Duration backoff(int attempts) {
        if (attempts >= 6) {
            return MAX_BACKOFF; // 2^6 = 64 s > 60 s
        }
        return Duration.ofSeconds(1L << Math.max(attempts, 1));
    }

    public UUID getId() {
        return id;
    }

    public String getQueue() {
        return queue;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
