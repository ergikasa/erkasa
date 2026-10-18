package com.erkasa.backend.providers.image;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.providers.clipdrop" in application.yml.
 *
 * Clipdrop charges in credits: 1 credit per successful call. Text-to-image always returns
 * a 1024x1024 square, so for 9:16 / 16:9 we make a second call (uncrop) that extends the
 * picture to the right shape: 2 credits per portrait or landscape image.
 *
 * @param apiKey        from CLIPDROP_API_KEY (https://clipdrop.co/apis/account)
 * @param baseUrl       https://clipdrop-api.co (tests point it to a fake server)
 * @param uncrop        true = extend the square to the video shape; false = return the square
 *                      and let the renderer crop it (1 credit, but less of the picture is used)
 * @param usdPerCredit  what one credit costs you; 0 while on the free credits
 * @param timeout       how long to wait for each call
 */
@ConfigurationProperties(prefix = "erkasa.providers.clipdrop")
public record ClipdropProperties(String apiKey,
                                 String baseUrl,
                                 Boolean uncrop,
                                 BigDecimal usdPerCredit,
                                 Duration timeout) {

    public ClipdropProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://clipdrop-api.co" : baseUrl;
        uncrop = uncrop == null ? Boolean.TRUE : uncrop;
        usdPerCredit = usdPerCredit == null ? BigDecimal.ZERO : usdPerCredit;
        timeout = timeout == null ? Duration.ofSeconds(90) : timeout;
    }
}