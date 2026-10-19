package com.erkasa.backend.providers.tts;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.providers.azure-speech" in application.yml.
 *
 * @param key                  from AZURE_SPEECH_KEY (Azure portal -> Speech resource -> Keys and Endpoint)
 * @param region               from AZURE_SPEECH_REGION, e.g. westeurope (same page)
 * @param usdPerThousandChars  neural voices price per 1,000 characters
 * @param timeout              how long to wait for one voice-over
 */
@ConfigurationProperties(prefix = "erkasa.providers.azure-speech")
public record AzureSpeechProperties(String key,
                                    String region,
                                    BigDecimal usdPerThousandChars,
                                    Duration timeout) {

    public AzureSpeechProperties {
        region = region == null || region.isBlank() ? "westeurope" : region;
        usdPerThousandChars = usdPerThousandChars == null ? new BigDecimal("0.015") : usdPerThousandChars;
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
    }
}