package com.erkasa.backend.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** V2 task A1: retry timing and bookkeeping of one outbox message (no Spring, no Docker). */
class OutboxMessageTests {

    @Test
    void backoffDoublesUpToOneMinute() {
        assertThat(OutboxMessage.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxMessage.backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(OutboxMessage.backoff(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(OutboxMessage.backoff(5)).isEqualTo(Duration.ofSeconds(32));
        assertThat(OutboxMessage.backoff(6)).isEqualTo(Duration.ofSeconds(60));
        assertThat(OutboxMessage.backoff(1000)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void aFailureIsCountedAndPostponed() {
        OutboxMessage m = OutboxMessage.create("render.requests", "{}");
        Instant now = Instant.parse("2026-11-04T10:00:00Z");

        m.markFailed("RabbitMQ unreachable", now);
        assertThat(m.getAttempts()).isEqualTo(1);
        assertThat(m.getLastError()).isEqualTo("RabbitMQ unreachable");
        assertThat(m.getNextAttemptAt()).isEqualTo(now.plusSeconds(2));
        assertThat(m.getSentAt()).isNull();

        m.markFailed("x".repeat(2000), now);
        assertThat(m.getAttempts()).isEqualTo(2);
        assertThat(m.getLastError()).hasSize(OutboxMessage.MAX_ERROR_LENGTH);
        assertThat(m.getNextAttemptAt()).isEqualTo(now.plusSeconds(4));
    }

    @Test
    void aSentMessageForgetsItsLastError() {
        OutboxMessage m = OutboxMessage.create("render.requests", "{}");
        Instant now = Instant.parse("2026-11-04T10:00:00Z");
        m.markFailed("No confirm", now);
        m.markSent(now.plusSeconds(3));
        assertThat(m.getSentAt()).isEqualTo(now.plusSeconds(3));
        assertThat(m.getAttempts()).isEqualTo(2);
        assertThat(m.getLastError()).isNull();
    }
}
