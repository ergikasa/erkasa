package com.erkasa.backend.features.promptvideo.render;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real time plus an offset the test moves forward ("31 minutes later") instead of sleeping.
 * With offset 0 it is the normal clock, so messages handled by this context between tests behave.
 */
final class MovableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    void advance(Duration by) {
        offset.updateAndGet(current -> current.plus(by));
    }

    void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
