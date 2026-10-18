package com.erkasa.backend.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.erkasa.backend.providers.image.ClipdropImageProvider;
import com.erkasa.backend.providers.image.ClipdropProperties;
import com.erkasa.backend.providers.image.GeneratedImage;
import com.erkasa.backend.providers.image.ImageRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The real ClipdropImageProvider against a FAKE Clipdrop server running inside the test.
 * text-to-image answers with a 1024x1024 PNG; uncrop answers with a taller/wider JPEG.
 */
class ClipdropImageProviderTests {

    private HttpServer server;
    private final AtomicReference<String> textBody = new AtomicReference<>();
    private final AtomicReference<String> uncropBody = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicInteger uncropCalls = new AtomicInteger();
    private volatile int textStatus = 200;
    private volatile String errorJson = "{}";

    private final byte[] square = image(1024, 1024, "png");
    private final byte[] portrait = image(1024, 1820, "jpg");
    private final byte[] landscape = image(1820, 1024, "jpg");

    @BeforeEach
    void startFakeClipdrop() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/text-to-image/v1", exchange -> {
            textBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            lastKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            if (textStatus == 200) {
                reply(exchange, 200, "image/png", square);
            } else {
                reply(exchange, textStatus, "application/json", errorJson.getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/uncrop/v1", exchange -> {
            uncropCalls.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            uncropBody.set(body);
            reply(exchange, 200, "image/jpeg", body.contains("extend_up") ? portrait : landscape);
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ClipdropImageProvider provider(boolean uncrop) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        ClipdropProperties props = new ClipdropProperties("test-clip-key", url, uncrop,
                new BigDecimal("0.01"), Duration.ofSeconds(5));
        return new ClipdropImageProvider(props, new ProviderHttp());
    }

    @Test
    void portraitIsGeneratedThenExtendedToNineBySixteen() {
        GeneratedImage image = provider(true).generate(new ImageRequest("A lighthouse at dawn", AspectRatio.PORTRAIT));

        assertThat(lastKey.get()).isEqualTo("test-clip-key");
        assertThat(textBody.get()).contains("name=\"prompt\"", "A lighthouse at dawn");
        // 1024 * 16/9 = 1820 -> 796 extra pixels, 398 on top and 398 at the bottom
        assertThat(uncropBody.get()).contains("name=\"image_file\"", "name=\"extend_up\"", "398",
                "name=\"extend_down\"").doesNotContain("extend_left");

        assertThat(image.data()).isEqualTo(portrait);
        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(image.width()).isEqualTo(1024);
        assertThat(image.height()).isEqualTo(1820);
        assertThat(image.usage().unitType()).isEqualTo("credits");
        assertThat(image.usage().outputUnits()).isEqualTo(2);
        assertThat(image.usage().costUsd()).isEqualByComparingTo("0.02");
    }

    @Test
    void landscapeIsExtendedLeftAndRight() {
        GeneratedImage image = provider(true).generate(new ImageRequest("A city skyline", AspectRatio.LANDSCAPE));

        assertThat(uncropBody.get()).contains("name=\"extend_left\"", "name=\"extend_right\"")
                .doesNotContain("extend_up");
        assertThat(image.width()).isEqualTo(1820);
        assertThat(image.height()).isEqualTo(1024);
    }

    @Test
    void squareNeedsOnlyOneCall() {
        GeneratedImage image = provider(true).generate(new ImageRequest("x", AspectRatio.SQUARE));

        assertThat(uncropCalls.get()).isZero();
        assertThat(image.data()).isEqualTo(square);
        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.usage().outputUnits()).isEqualTo(1);
    }

    @Test
    void uncropOffReturnsTheSquare() {
        GeneratedImage image = provider(false).generate(new ImageRequest("x", AspectRatio.PORTRAIT));

        assertThat(uncropCalls.get()).isZero();
        assertThat(image.width()).isEqualTo(1024);
        assertThat(image.height()).isEqualTo(1024);
    }

    @Test
    void longPromptIsCutTo1000Characters() {
        provider(false).generate(new ImageRequest("a".repeat(1500), AspectRatio.SQUARE));

        assertThat(textBody.get()).contains("a".repeat(1000)).doesNotContain("a".repeat(1001));
    }

    @Test
    void noCreditsIsNotRetryable() {
        textStatus = 402;
        errorJson = "{\"error\":\"Insufficient credits\"}";

        assertThatThrownBy(() -> provider(true).generate(new ImageRequest("x", AspectRatio.PORTRAIT)))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("NO_CREDITS");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void rateLimitIsRetryable() {
        textStatus = 429;
        errorJson = "{\"error\":\"Too many requests\"}";

        assertThatThrownBy(() -> provider(true).generate(new ImageRequest("x", AspectRatio.PORTRAIT)))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("RATE_LIMITED");
                    assertThat(e.isRetryable()).isTrue();
                });
    }

    @Test
    void badKeyIsNotRetryable() {
        textStatus = 401;
        errorJson = "{\"error\":\"Invalid api key\"}";

        assertThatThrownBy(() -> provider(true).generate(new ImageRequest("x", AspectRatio.PORTRAIT)))
                .isInstanceOfSatisfying(ProviderException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("AUTH_FAILED");
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void missingKeyFailsAtStartup() {
        ClipdropProperties noKey = new ClipdropProperties("", null, null, null, null);

        assertThatThrownBy(() -> new ClipdropImageProvider(noKey, new ProviderHttp()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLIPDROP_API_KEY");
    }

    // ---------- helpers ----------

    private static void reply(HttpExchange exchange, int code, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        if (code == 200) {
            exchange.getResponseHeaders().add("x-credits-consumed", "1");
            exchange.getResponseHeaders().add("x-remaining-credits", "97");
        }
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static byte[] image(int width, int height, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}