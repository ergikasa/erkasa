package com.erkasa.backend.providers.tts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.tts.AzureWordTimings.Boundary;

/** Azure word-boundary events -> subtitle words. No key needed: the SDK is not called. */
class AzureWordTimingsTests {

    /** seconds -> Azure ticks (100 ns) */
    private static long t(double seconds) {
        return Math.round(seconds * AzureWordTimings.TICKS_PER_SECOND);
    }

    @Test
    void convertsTicksToSeconds() {
        List<WordTiming> words = AzureWordTimings.toWords(List.of(
                new Boundary("Mirë", t(0.05), t(0.30), false),
                new Boundary("dita", t(0.40), t(0.25), false)));

        assertThat(words).containsExactly(
                new WordTiming("Mirë", 0.05, 0.35),
                new WordTiming("dita", 0.4, 0.65));
    }

    @Test
    void punctuationIsGluedToThePreviousWord() {
        List<WordTiming> words = AzureWordTimings.toWords(List.of(
                new Boundary("Përshëndetje", t(0.0), t(0.6), false),
                new Boundary(",", t(0.6), t(0.1), true),
                new Boundary("Shqipëri", t(0.9), t(0.5), false),
                new Boundary("!", t(1.4), t(0.05), true)));

        assertThat(words).containsExactly(
                new WordTiming("Përshëndetje,", 0.0, 0.7),
                new WordTiming("Shqipëri!", 0.9, 1.45));
    }

    @Test
    void leadingPunctuationAndBlanksAreSkipped() {
        List<WordTiming> words = AzureWordTimings.toWords(List.of(
                new Boundary("\"", t(0.0), t(0.0), true),
                new Boundary(" ", t(0.0), t(0.0), false),
                new Boundary("Здраво", t(0.1), t(0.4), false)));

        assertThat(words).containsExactly(new WordTiming("Здраво", 0.1, 0.5));
    }

    @Test
    void missingKeyFailsOnlyWhenUsed() {
        AzureEngine engine = new AzureEngine(new AzureSpeechProperties("", null, BigDecimal.ONE, Duration.ofSeconds(1)));

        assertThat(engine.configured()).isFalse();
        try {
            engine.synthesize("Tungjatjeta", Language.SQ, "sq-AL-IlirNeural");
        } catch (ProviderException e) {
            assertThat(e.getCode()).isEqualTo("NOT_CONFIGURED");
            assertThat(e.isRetryable()).isFalse();
            return;
        }
        throw new AssertionError("expected NOT_CONFIGURED");
    }
}