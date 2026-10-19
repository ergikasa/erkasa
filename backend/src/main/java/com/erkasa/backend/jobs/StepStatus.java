package com.erkasa.backend.jobs;

import java.util.EnumSet;
import java.util.Set;

/**
 * PENDING -> RUNNING -> SUCCEEDED (final: never paid for twice)
 *                    -> FAILED -> PENDING (retry)
 * RUNNING -> PENDING is allowed for a worker that crashed mid-step (picked up again on restart).
 * SKIPPED: not needed any more, e.g. the job was cancelled before this step started.
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED;

    public Set<StepStatus> next() {
        return switch (this) {
            case PENDING -> EnumSet.of(RUNNING, SKIPPED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, PENDING);
            case FAILED -> EnumSet.of(PENDING, SKIPPED);
            case SUCCEEDED, SKIPPED -> EnumSet.noneOf(StepStatus.class);
        };
    }

    public boolean canMoveTo(StepStatus target) {
        return next().contains(target);
    }

    public boolean isDone() {
        return this == SUCCEEDED || this == SKIPPED;
    }
}