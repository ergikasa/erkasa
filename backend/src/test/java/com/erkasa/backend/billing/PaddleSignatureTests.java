package com.erkasa.backend.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** Paddle-Signature: ts=...;h1=hex(HMAC-SHA256(secret, ts:body)). Plain unit tests. */
class PaddleSignatureTests {

    private static final String SECRET = "pdl_ntfset_secret";
    private static final String BODY = "{\"event_type\":\"transaction.completed\"}";
    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static String header(long ts, String secret, String body) {
        return "ts=" + ts + ";h1=" + PaddleSignature.hex(secret, ts + ":" + body);
    }

    @Test
    void aCorrectSignatureIsAccepted() {
        assertThat(PaddleSignature.isValid(header(NOW, SECRET, BODY), BODY, SECRET, TOLERANCE, CLOCK)).isTrue();
    }

    @Test
    void aChangedBodyOrWrongSecretIsRejected() {
        assertThat(PaddleSignature.isValid(header(NOW, SECRET, BODY), BODY.replace("completed", "paid"), SECRET, TOLERANCE, CLOCK)).isFalse();
        assertThat(PaddleSignature.isValid(header(NOW, "other_secret", BODY), BODY, SECRET, TOLERANCE, CLOCK)).isFalse();
    }

    @Test
    void anOldSignatureIsRejected() {
        assertThat(PaddleSignature.isValid(header(NOW - 600, SECRET, BODY), BODY, SECRET, TOLERANCE, CLOCK)).isFalse();
        assertThat(PaddleSignature.isValid(header(NOW - 120, SECRET, BODY), BODY, SECRET, TOLERANCE, CLOCK)).isTrue();
    }

    @Test
    void duringSecretRotationOneMatchingH1IsEnough() {
        String both = "ts=" + NOW + ";h1=" + PaddleSignature.hex("old_secret", NOW + ":" + BODY)
                + ";h1=" + PaddleSignature.hex(SECRET, NOW + ":" + BODY);
        assertThat(PaddleSignature.isValid(both, BODY, SECRET, TOLERANCE, CLOCK)).isTrue();
    }

    @Test
    void garbageIsRejected() {
        assertThat(PaddleSignature.isValid(null, BODY, SECRET, TOLERANCE, CLOCK)).isFalse();
        assertThat(PaddleSignature.isValid("nonsense", BODY, SECRET, TOLERANCE, CLOCK)).isFalse();
        assertThat(PaddleSignature.isValid("ts=abc;h1=zz", BODY, SECRET, TOLERANCE, CLOCK)).isFalse();
        // no secret configured: nothing is accepted
        assertThat(PaddleSignature.isValid(header(NOW, SECRET, BODY), BODY, "", TOLERANCE, CLOCK)).isFalse();
    }
}
