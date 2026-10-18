package com.erkasa.backend.providers.image;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.providers.fal" in application.yml.
 *
 * Price is in US dollars per generated image. Check it on the model page when you change
 * model or resolution: https://fal.ai/models/fal-ai/nano-banana-2
 *
 * @param apiKey          from FAL_KEY (infra/.env locally, server variable in production)
 * @param model           fal endpoint id, e.g. fal-ai/nano-banana-2 (change model = change only this)
 * @param baseUrl         https://fal.run (tests point it to a fake server)
 * @param resolution      0.5K, 1K, 2K or 4K: 1K is plenty for a 720p/1080p video
 * @param outputFormat    jpeg, png or webp
 * @param safetyTolerance "1" (strictest) .. "6"
 * @param usdPerImage     price of one image at the resolution above
 * @param timeout         how long to wait for one image
 */
@ConfigurationProperties(prefix = "erkasa.providers.fal")
public record FalProperties(String apiKey,
                            String model,
                            String baseUrl,
                            String resolution,
                            String outputFormat,
                            String safetyTolerance,
                            BigDecimal usdPerImage,
                            Duration timeout) {

    public FalProperties {
        model = blank(model) ? "fal-ai/nano-banana-2" : model;
        baseUrl = blank(baseUrl) ? "https://fal.run" : baseUrl;
        resolution = blank(resolution) ? "1K" : resolution;
        outputFormat = blank(outputFormat) ? "png" : outputFormat;
        safetyTolerance = blank(safetyTolerance) ? "4" : safetyTolerance;
        usdPerImage = usdPerImage == null ? new BigDecimal("0.08") : usdPerImage;
        timeout = timeout == null ? Duration.ofSeconds(120) : timeout;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}