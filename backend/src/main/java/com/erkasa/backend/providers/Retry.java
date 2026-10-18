package com.erkasa.backend.providers;


import java.time.Duration;
import java.util.function.Supplier;

/**
 * Runs a provider call again when it fails with a retryable error, waiting longer each time
 * ("exponential backoff"): 1 s, then 2 s, then 4 s... This gives an overloaded provider time
 * to recover instead of hammering it.
 *
 * Non-retryable errors (bad key, refused content) are thrown at once: retrying cannot help.
 */
public final class Retry {

    private Retry() {
    }

    public static <T> T run(int maxAttempts, Duration initialDelay, Supplier<T> call) {
        Duration delay = initialDelay;
        for (int attempt = 1; ; attempt++) {
            try {
                return call.get();
            } catch (ProviderException e) {
                if (!e.isRetryable() || attempt >= maxAttempts) {
                    throw e;
                }
                sleep(delay);
                delay = delay.multipliedBy(2);
            }
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}