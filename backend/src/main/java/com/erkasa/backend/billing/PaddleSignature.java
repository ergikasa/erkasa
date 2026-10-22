package com.erkasa.backend.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Checks the Paddle-Signature header of a webhook: "ts=1671552777;h1=eb4d0d...".
 * h1 = hex(HMAC-SHA256(secret, ts + ":" + raw body)). During a secret rotation there can be
 * two h1 values; one matching is enough.
 * Docs: https://developer.paddle.com/webhooks/signature-verification
 */
public final class PaddleSignature {

    private PaddleSignature() {
    }

    public static boolean isValid(String header, String rawBody, String secret, Duration tolerance, Clock clock) {
        if (header == null || header.isBlank() || rawBody == null || secret == null || secret.isBlank()) {
            return false;
        }
        String ts = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("ts")) {
                ts = value;
            } else if (key.equals("h1")) {
                signatures.add(value);
            }
        }
        if (ts == null || signatures.isEmpty()) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return false;
        }
        long age = clock.instant().getEpochSecond() - seconds;
        if (Math.abs(age) > tolerance.toSeconds()) {
            return false; // too old (a replay) or from the future
        }
        byte[] expected = sign(secret, ts + ":" + rawBody);
        for (String h1 : signatures) {
            byte[] given;
            try {
                given = HexFormat.of().parseHex(h1);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (MessageDigest.isEqual(expected, given)) { // constant time
                return true;
            }
        }
        return false;
    }

    /** hex HMAC-SHA256, e.g. for tests that build a valid header. */
    public static String hex(String secret, String payload) {
        return HexFormat.of().formatHex(sign(secret, payload));
    }

    private static byte[] sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
