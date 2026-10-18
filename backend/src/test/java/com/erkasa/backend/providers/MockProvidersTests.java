package com.erkasa.backend.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.erkasa.backend.providers.image.GeneratedImage;
import com.erkasa.backend.providers.image.ImageRequest;
import com.erkasa.backend.providers.image.MockImageProvider;
import com.erkasa.backend.providers.llm.LlmRequest;
import com.erkasa.backend.providers.llm.MockLlmProvider;
import com.erkasa.backend.providers.tts.MockTtsProvider;
import com.erkasa.backend.providers.tts.SpeechRequest;
import com.erkasa.backend.providers.tts.SpeechResult;
import com.erkasa.backend.providers.tts.WordTiming;

/** Plain unit tests of the free mock providers: no Spring, no Docker. */
class MockProvidersTests {

    @BeforeAll
    static void headless() {
        // Draw images without a screen (always true on servers; set here for local runs too).
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void mockImageHasTheRequestedShape() throws Exception {
        MockImageProvider provider = new MockImageProvider();

        for (AspectRatio ratio : AspectRatio.values()) {
            GeneratedImage image = provider.generate(new ImageRequest("A lighthouse at sunset", ratio));
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.data()));

            assertThat(image.contentType()).isEqualTo("image/png");
            assertThat(decoded.getWidth()).isEqualTo(ratio.mockWidth());
            assertThat(decoded.getHeight()).isEqualTo(ratio.mockHeight());
            assertThat(image.usage().outputUnits()).isEqualTo(1);
        }
    }

    @Test
    void mockSpeechIsAPlayableWavWithOneTimingPerWord() {
        SpeechResult speech = new MockTtsProvider().synthesize(
                new SpeechRequest("Mirë se vini në erkasa. Ky është një test!", Language.SQ, null));

        byte[] header = Arrays.copyOfRange(speech.audio(), 0, 4);
        assertThat(new String(header, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(speech.contentType()).isEqualTo("audio/wav");

        List<WordTiming> words = speech.words();
        assertThat(words).extracting(WordTiming::word)
                .containsExactly("Mirë", "se", "vini", "në", "erkasa.", "Ky", "është", "një", "test!");

        // Each word starts after the previous one ended, and the audio lasts past the last word.
        for (int i = 1; i < words.size(); i++) {
            assertThat(words.get(i).startSeconds()).isGreaterThanOrEqualTo(words.get(i - 1).endSeconds());
        }
        assertThat(speech.durationSeconds()).isGreaterThan(words.get(words.size() - 1).endSeconds());
    }

    @Test
    void mockLlmEchoesThePromptAndCostsNothing() {
        var response = new MockLlmProvider().generate(new LlmRequest("system", "Explain black holes", 500));

        assertThat(response.text()).contains("Explain black holes");
        assertThat(response.usage().costUsd()).isZero();
        assertThat(response.usage().inputUnits()).isPositive();
    }
}