package com.erkasa.backend.auth;

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

/** One row of the users table (see V2__users.sql). */
@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** Google's permanent id for this person. NULL if the user never used "Sign in with Google". */
    @Column(name = "google_sub", length = 255)
    private String googleSub;

    /** Balance (phase 7): 1 credit = 1 second of video. Changed only by CreditService. */
    @Column(nullable = false)
    private int credits;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Use {@link #newLocalUser} in our own code. */
    protected User() {
    }

    /** A user who registered with email + password. */
    public static User newLocalUser(String email, String passwordHash, String displayName) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = email;
        user.passwordHash = passwordHash;
        user.displayName = displayName;
        user.emailVerified = false;
        user.role = Role.USER;
        return user;
    }

    /** A user who signed up with Google. No password; the email is verified by Google. */
    public static User newGoogleUser(String email, String googleSub, String displayName) {
        User user = new User();
        user.id = UUID.randomUUID();
        user.email = email;
        user.passwordHash = null;
        user.displayName = displayName;
        user.emailVerified = true;
        user.role = Role.USER;
        user.googleSub = googleSub;
        return user;
    }

    /**
     * Connects Google to an existing account with the same email.
     *
     * If that email was never verified, the password is removed. Reason: anyone can register
     * with someone else's email. Without this, a person who pre-registered "victim@gmail.com"
     * with their own password could still log in after the real owner signs in with Google.
     */
    public void linkGoogle(String googleSub) {
        if (!this.emailVerified) {
            this.passwordHash = null;
        }
        this.googleSub = googleSub;
        this.emailVerified = true;
    }

    /** The user proved they can read emails sent to this address. */
    public void markEmailVerified() {
        this.emailVerified = true;
    }

    /** + or - credits. Never below 0 (the database checks it too). Use CreditService, not this. */
    public void addCredits(int amount) {
        if (credits + amount < 0) {
            throw new IllegalStateException("Credits would go below 0");
        }
        credits += amount;
    }

    public int getCredits() {
        return credits;
    }

    /** Stores a new BCrypt hash (never the password itself). */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
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

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Role getRole() {
        return role;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}