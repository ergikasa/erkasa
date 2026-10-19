package com.erkasa.backend.providers.tts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.erkasa.backend.providers.Language;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.ProviderHttp;
import com.sun.net.httpserver.HttpServer;

/** The real ElevenLabsEngine against a FAKE ElevenLabs server. No key, no internet, no cost. */
class ElevenLabsEngineTests {

    private static final String VOICE = "nPczCjzI2devNBz1zQrb";

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseJson;

    /** 16,000 bytes of "MP3" at 128 kbit/s = exactly 1.0 second. */
    private final byte[] audio = new byte[16_000];

    @BeforeEach
    void startFakeElevenLabs() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/text-to-speech/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastQuery.set(exchange.getRequestURI().getQuery());
            lastKey.set(exchange.getRequestHeaders().getFirst("xi-api-key"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        responseJson = answer("Hi, there", 0.0, 0.08);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ElevenLabsEngine engine(String key) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        ElevenLabsProperties props = new ElevenLabsProperties(key, "eleven_v4", url, "mp3_44100_128",
                new BigDecimal("0.10"), Duration.ofSeconds(5));
        return new ElevenLabsEngine(props, new ProviderHttp());
    }

    /** A with-timestamps answer where every character lasts `step` seconds, starting at `start`. */
    private String answer(String text, double start, double step) {
        List<String> chars = new ArrayList<>();
        List<String> starts = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        double t = start;
        for (char c : text.toCharArray()) {
            chars.add("\"" + c + "\"");
            starts.add(String.valueOf(t));
            ends.add(String.valueOf(t + step));
            t += step;
        }
        return """
                {"audio_base64":"%s",
                 "alignment":{"characters":[%s],
                              "character_start_times_seconds":[%s],
                              "character_end_times_seconds":[%s]},
                 "normalized_alignment":null}
                """.formatted(Base64.getEncoder().encodeToString(audio),
                String.join(",", chars), String.join(",", starts), String.join(",", ends));
    }

    @Test
    void sendsTheRightRequestAndReturnsAudioWordsAndCost() {
        SpeechResult result = engine("test-xi-key").synthesize("Hi, there", Language.EN, VOICE);

        assertThat(lastKey.get()).isEqualTo("test-xi-key");
        assertThat(lastPath.get()).isEqualTo("/v1/text-to-speech/" + VOICE + "/with-timestamps");
        assertThat(lastQuery.get()).isEqualTo("output_format=mp3_44100_128");
        assertThat(lastBody.get()).contains("\"text\":\"Hi, there\"", "\"model_id\":\"eleven_v4\"",
                "\"language_code\":\"en\"");

        assertThat(result.audio()).isEqualTo(audio);
        assertThat(result.contentType()).isEqualTo("audio/mpeg");
        // "Hi," = chars 0..2 (0.00-0.24), space, "there" = chars 4..8 (0.32-0.72)
        assertThat(result.words()).containsExactly(
                new WordTiming("Hi,", 0.0, 0.24),
                new WordTiming("there", 0.32, 0.72));
        // the MP3 is 1.0 s long, longer than the last word
        assertThat(result.durationSeconds()).isEqualTo(1.0);

        assertThat(result.usage().model()).isEqualTo("elevenlabs/eleven_v4");
        assertThat(result.usage().unitType()).isEqualTo("characters");
        assertThat(result.usage().inputUnits()).isEqualTo(9);
        // 9 chars x $0.10 / 1000
        assertThat(result.usage().costUsd()).isEqualByComparingTo("0.0009");
    }

    @Test
    void cyrillicAndAccentsKeepTheirWords() {
        responseJson = answer("Привет мир", 0.1, 0.05);

        SpeechResult result = engine("k").synthesize("Привет мир", Language.RU, VOICE);

        assertThat(result.words()).extracting(WordTiming::word).containsExactly("Привет", "мир");
        assertThat(lastBody.get()).contains("\"language_code\":\"ru\"");
    }

    @Test
    void groupsCharactersIntoWords() {
        List<WordTiming> words = ElevenLabsEngine.words(
                List.of("A", " ", " ", "b", "c", "."),
                List.of(0.0, 0.1, 0.2, 0.3, 0.4, 0.5),
                List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6));

        assertThat(words).containsExactly(new WordTiming("A", 0.0, 0.1), new WordTiming("bc.", 0.3, 0.6));
    }

    @Test
    void durationFromBitrate() {
        assertThat(ElevenLabsEngine.mp3Seconds(32_000, "mp3_44100_128")).isEqualTo(2.0);
        assertThat(ElevenLabsEngine.mp3Seconds(32_000, "pcm_44100")).isZero();
    }

    @Test
    void quotaExceededIsNotRetryable() {
        status = 401;
        responseJson = "{\"detail\":{\"status\":\"quota_exceeded\",\"message\":\"You have 0 credits\"}}";

        assertThatThrownBy(() -> engine("k").synthesize("Hello", Language.EN, VOICE))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("NO_CREDITS");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void badKeyIsNotRetryable() {
        status = 401;
        responseJson = "{\"detail\":{\"status\":\"invalid_api_key\"}}";

        assertThatThrownBy(() -> engine("k").synthesize("Hello", Language.EN, VOICE))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("AUTH_FAILED");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void rateLimitIsRetryable() {
        status = 429;
        responseJson = "{\"detail\":{\"status\":\"too_many_concurrent_requests\"}}";

        assertThatThrownBy(() -> engine("k").synthesize("Hello", Language.EN, VOICE))
                .isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.isRetryable()).isTrue());
    }

    @Test
    void missingKeyFailsOnlyWhenUsed() {
        ElevenLabsEngine engine = engine("");

        assertThat(engine.configured()).isFalse();
        assertThatThrownBy(() -> engine.synthesize("Hello", Language.EN, VOICE))
                .isInstanceOfSatisfying(ProviderException.class,
                        e -> assertThat(e.getCode()).isEqualTo("NOT_CONFIGURED"));
    }
}