package com.erkasa.backend.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** One row of email_tokens (see V5__email_tokens.sql). Holds only the hash of the link's token. */
@Entity
@Table(name = "email_tokens")
public class EmailToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmailTokenPurpose purpose;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EmailToken() {
    }

    public static EmailToken create(UUID userId, EmailTokenPurpose purpose, String tokenHash, Instant expiresAt) {
        EmailToken token = new EmailToken();
        token.id = UUID.randomUUID();
        token.userId = userId;
        token.purpose = purpose;
        token.tokenHash = tokenHash;
        token.expiresAt = expiresAt;
        return token;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    /** Not used yet and not expired. */
    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public EmailTokenPurpose getPurpose() {
        return purpose;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}