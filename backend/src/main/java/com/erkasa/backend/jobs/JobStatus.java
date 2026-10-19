package com.erkasa.backend.jobs;

import java.util.EnumSet;
import java.util.Set;

/**
 * QUEUED -> RUNNING -> SUCCEEDED, or FAILED (retry goes back to QUEUED), or CANCELLED.
 * RUNNING -> QUEUED only for recovery: the server stopped while the job was running (4.4).
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public Set<JobStatus> next() {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED, QUEUED);
            case FAILED -> EnumSet.of(QUEUED, CANCELLED);
            case SUCCEEDED, CANCELLED -> EnumSet.noneOf(JobStatus.class);
        };
    }

    public boolean canMoveTo(JobStatus target) {
        return next().contains(target);
    }

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}