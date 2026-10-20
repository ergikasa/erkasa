package com.erkasa.backend.providers.tts;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.providers.elevenlabs" in application.yml.
 *
 * @param apiKey                 from ELEVENLABS_API_KEY (https://elevenlabs.io/app/settings/api-keys)
 * @param model                  eleven_v4 (best quality, 90+ languages) or eleven_multilingual_v2
 * @param baseUrl                https://api.elevenlabs.io (tests point it to a fake server)
 * @param outputFormat           mp3_44100_128: the duration is computed from this bitrate
 * @param usdPerThousandChars    what 1,000 characters cost on your plan
 * @param timeout                how long to wait for one voice-over
 * @param maxConcurrent          how many voice-overs run at the same time. Your plan's limit:
 *                               Free 2, Starter 3, Creator 5 (more = HTTP 429 concurrent_limit_exceeded)
 */
@ConfigurationProperties(prefix = "erkasa.providers.elevenlabs")
public record ElevenLabsProperties(String apiKey,
                                   String model,
                                   String baseUrl,
                                   String outputFormat,
                                   BigDecimal usdPerThousandChars,
                                   Duration timeout,
                                   Integer maxConcurrent) {

    public ElevenLabsProperties {
        model = blank(model) ? "eleven_v4" : model;
        baseUrl = blank(baseUrl) ? "https://api.elevenlabs.io" : baseUrl;
        outputFormat = blank(outputFormat) ? "mp3_44100_128" : outputFormat;
        usdPerThousandChars = usdPerThousandChars == null ? new BigDecimal("0.10") : usdPerThousandChars;
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
        maxConcurrent = maxConcurrent == null || maxConcurrent < 1 ? 2 : maxConcurrent;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
