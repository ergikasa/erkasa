package com.erkasa.backend.providers;



import java.math.BigDecimal;

/**
 * What one provider call consumed, as reported or estimated by the provider.
 *
 * @param model       exact model name used
 * @param unitType    what the units count: "tokens", "images", "characters"
 * @param inputUnits  e.g. prompt tokens, or characters of text sent to TTS
 * @param outputUnits e.g. generated tokens, or number of images
 * @param costUsd     cost of this call in US dollars
 */
public record ProviderUsage(String model, String unitType, long inputUnits, long outputUnits, BigDecimal costUsd) {

    public static ProviderUsage free(String model, String unitType, long inputUnits, long outputUnits) {
        return new ProviderUsage(model, unitType, inputUnits, outputUnits, BigDecimal.ZERO);
    }
}