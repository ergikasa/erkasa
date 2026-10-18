package com.erkasa.backend.providers;



import java.util.UUID;

/**
 * Who a provider call is for, so its cost lands on the right user and video job.
 * Both may be null (e.g. a dev test call without a job).
 */
public record CallContext(UUID userId, UUID jobId) {

    public static CallContext none() {
        return new CallContext(null, null);
    }

    public static CallContext forUser(UUID userId) {
        return new CallContext(userId, null);
    }
}