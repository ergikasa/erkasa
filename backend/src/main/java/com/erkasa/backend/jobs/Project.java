package com.erkasa.backend.jobs;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.erkasa.backend.providers.AspectRatio;
import com.erkasa.backend.providers.Language;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** One row of projects (see V8__projects_jobs.sql): one video a user is making. */
@Entity
@Table(name = "projects")
public class Project {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 1000)
    private String idea;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Language language;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "aspect_ratio", nullable = false, length = 10)
    private AspectRatio aspectRatio;

    @Column(name = "voice_id", length = 100)
    private String voiceId;

    @Column(length = 200)
    private String title;

    /** The script as JSON (VideoScript). Kept as text here; the service converts it. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String script;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Project() {
    }

    /** A new project starts by waiting for its script. */
    public static Project create(UUID userId, String idea, Language language, int durationSeconds,
                                 AspectRatio aspectRatio, String voiceId) {
        Project p = new Project();
        p.id = UUID.randomUUID();
        p.userId = userId;
        p.idea = idea;
        p.language = language;
        p.durationSeconds = durationSeconds;
        p.aspectRatio = aspectRatio;
        p.voiceId = voiceId;
        p.status = ProjectStatus.SCRIPT_PENDING;
        return p;
    }

    /** Moves to the next state, or throws if that move is not allowed from the current one. */
    public void moveTo(ProjectStatus target) {
        if (!status.canMoveTo(target)) {
            throw new InvalidTransitionException("project " + id, status, target);
        }
        status = target;
        if (target != ProjectStatus.FAILED) {
            errorCode = null;
            errorMessage = null;
        }
    }

    public void fail(String code, String message) {
        moveTo(ProjectStatus.FAILED);
        errorCode = code;
        errorMessage = Text.shorten(message, 500);
    }

    /** The LLM wrote a script, or the user edited it during review. */
    public void setScript(String title, String scriptJson) {
        this.title = Text.shorten(title, 200);
        this.script = scriptJson;
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

    public UUID getUserId() {
        return userId;
    }

    public String getIdea() {
        return idea;
    }

    public Language getLanguage() {
        return language;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public AspectRatio getAspectRatio() {
        return aspectRatio;
    }

    public String getVoiceId() {
        return voiceId;
    }

    public String getTitle() {
        return title;
    }

    public String getScript() {
        return script;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}