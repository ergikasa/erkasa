package com.erkasa.backend.common.web;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Counts requests per key in fixed time windows.
 *
 * Example with limit 10 / 5 minutes: the first request starts a 5-minute window; requests 1-10
 * pass; the 11th is rejected until that window ends, then counting starts again from zero.
 *
 * Simple on purpose. Two things to know:
 * - A caller can send 10 at the end of one window and 10 at the start of the next (20 in a moment).
 *   Fine for protecting logins and emails; a stricter algorithm would be overkill here.
 * - The counters live in this server's memory. With several backend servers each would count
 *   separately; when we get there, the counters move to a shared store (e.g. Redis).
 */
@Component
public class RateLimiter {

    /** Clean out finished windows every N checks, so the map cannot grow forever. */
    private static final int SWEEP_EVERY = 500;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger checksSinceSweep = new AtomicInteger();

    /** @param retryAfterSeconds how long until the caller may try again (0 when allowed) */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    public Decision check(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMs = window.toMillis();

        // compute() runs atomically for this key, so parallel requests cannot miscount.
        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.startMillis() >= windowMs) {
                return new Window(now, 1);
            }
            // Stop counting a little above the limit: the exact number past it does not matter.
            return new Window(existing.startMillis(), Math.min(existing.count() + 1, limit + 1));
        });

        if (checksSinceSweep.incrementAndGet() >= SWEEP_EVERY) {
            checksSinceSweep.set(0);
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= windowMs);
        }

        if (current.count() <= limit) {
            return new Decision(true, 0);
        }
        long msLeft = windowMs - (now - current.startMillis());
        return new Decision(false, Math.max(1, (msLeft + 999) / 1000));
    }

    /** Forgets every counter. Used by tests. */
    public void clear() {
        windows.clear();
    }

    private record Window(long startMillis, int count) {
    }
}