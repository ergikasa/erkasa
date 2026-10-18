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
        llm = llm == null ? "mock" : llm;
        image = image == null ? "mock" : image;
        tts = tts == null ? "mock" : tts;
        retry = retry == null ? new RetrySettings(3, Duration.ofSeconds(1)) : retry;
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
