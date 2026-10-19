package com.erkasa.backend.jobs;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a video project is. The happy path:
 *
 *   SCRIPT_PENDING -> SCRIPT_REVIEW -> ASSETS_PENDING -> ASSETS_READY -> RENDERING -> DONE
 *     (LLM writes)    (user checks)    (images+voices)   (all files ok)   (phase 5)
 *
 * Any working state can go to FAILED (and be retried from there) or CANCELLED (final).
 */
public enum ProjectStatus {
    SCRIPT_PENDING,
    SCRIPT_REVIEW,
    ASSETS_PENDING,
    ASSETS_READY,
    RENDERING,
    DONE,
    FAILED,
    CANCELLED;

    /** The states this one may move to. Everything else is a bug or a stale request. */
    public Set<ProjectStatus> next() {
        return switch (this) {
            case SCRIPT_PENDING -> EnumSet.of(SCRIPT_REVIEW, FAILED, CANCELLED);
            // back to SCRIPT_PENDING = "write me a new script"
            case SCRIPT_REVIEW -> EnumSet.of(SCRIPT_PENDING, ASSETS_PENDING, CANCELLED);
            case ASSETS_PENDING -> EnumSet.of(ASSETS_READY, FAILED, CANCELLED);
            // back to ASSETS_PENDING = "redo this scene's image"
            case ASSETS_READY -> EnumSet.of(RENDERING, ASSETS_PENDING, CANCELLED);
            case RENDERING -> EnumSet.of(DONE, FAILED, CANCELLED);
            // retry restarts the stage that failed
            case FAILED -> EnumSet.of(SCRIPT_PENDING, ASSETS_PENDING, RENDERING, CANCELLED);
            case DONE, CANCELLED -> EnumSet.noneOf(ProjectStatus.class);
        };
    }

    public boolean canMoveTo(ProjectStatus target) {
        return next().contains(target);
    }

    /** No more work will happen on its own. */
    public boolean isFinal() {
        return this == DONE || this == CANCELLED;
    }
}