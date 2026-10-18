package com.erkasa.backend.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Plain unit test: no Spring, no Docker, runs in milliseconds. */
class RetryTests {

    private static final Duration TINY = Duration.ofMillis(1);

    private static ProviderException temporary() {
        return new ProviderException("test", "TIMEOUT", "slow", true, null);
    }

    private static ProviderException permanent() {
        return new ProviderException("test", "AUTH_FAILED", "bad key", false, null);
    }

    @Test
    void retriesTemporaryErrorsUntilItWorks() {
        AtomicInteger calls = new AtomicInteger();

        String result = Retry.run(3, TINY, () -> {
            if (calls.incrementAndGet() < 3) {
                throw temporary();
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> Retry.run(3, TINY, () -> {
            calls.incrementAndGet();
            throw temporary();
        })).isInstanceOf(ProviderException.class);

        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryPermanentErrors() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> Retry.run(3, TINY, () -> {
            calls.incrementAndGet();
            throw permanent();
        })).isInstanceOf(ProviderException.class).hasMessageContaining("AUTH_FAILED");

        assertThat(calls).hasValue(1);
    }

    @Test
    void httpStatusesAreClassified() {
        assertThat(ProviderException.fromHttpStatus("p", 429, "").isRetryable()).isTrue();
        assertThat(ProviderException.fromHttpStatus("p", 503, "").isRetryable()).isTrue();
        assertThat(ProviderException.fromHttpStatus("p", 401, "").isRetryable()).isFalse();
        assertThat(ProviderException.fromHttpStatus("p", 400, "").getCode()).isEqualTo("BAD_REQUEST");
    }
}