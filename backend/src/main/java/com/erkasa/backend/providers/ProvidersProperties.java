package com.erkasa.backend.providers;


import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.providers" in application.yml.
 *
 * @param llm   which LLM implementation: "mock" (default) or "anthropic" (3.2)
 * @param image which image implementation: "mock" (default) or "fal" (3.3)
 * @param tts   which TTS implementation: "mock" (default) or "elevenlabs-azure" (3.4)
 * @param retry how failed calls are retried
 */
@ConfigurationProperties(prefix = "erkasa.providers")
public record ProvidersProperties(String llm, String image, String tts, RetrySettings retry) {

    public ProvidersProperties {
        llm = check("llm", "PROVIDER_LLM", llm, "mock", "anthropic");
        image = check("image", "PROVIDER_IMAGE", image, "mock", "fal", "clipdrop");
        tts = check("tts", "PROVIDER_TTS", tts, "mock", "elevenlabs-azure");
        retry = retry == null ? new RetrySettings(3, Duration.ofSeconds(1)) : retry;
    }

    /**
     * Stops the app at startup with a clear message when a value is misspelled, e.g.
     * "PROVIDER_TTS=elevenlabs" or "PROVIDER_TTS=elevenlabs-azure # comment" in infra/.env.
     * (In .env, a trailing space or a comment after the value becomes part of the value.)
     */
    private static String check(String name, String envVar, String value, String... allowed) {
        if (value == null || value.isBlank()) {
            return "mock";
        }
        for (String a : allowed) {
            if (a.equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("erkasa.providers." + name + " = '" + value + "' is not valid. "
                + "Set " + envVar + " in infra/.env to exactly one of " + String.join(", ", allowed)
                + " (no spaces, no comment on the same line).");
    }

    /**
     * @param maxAttempts  total tries including the first (3 = first try + 2 retries)
     * @param initialDelay wait before the first retry; doubles each time
     */
    public record RetrySettings(int maxAttempts, Duration initialDelay) {

        public RetrySettings {
            maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
            initialDelay = initialDelay == null ? Duration.ofSeconds(1) : initialDelay;
        }
    }
}