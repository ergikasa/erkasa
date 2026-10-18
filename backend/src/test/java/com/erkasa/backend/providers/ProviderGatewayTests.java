package com.erkasa.backend.providers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.providers.image.ImageRequest;
import com.erkasa.backend.providers.llm.LlmRequest;

/** Sub-task 3.1: calls go through the gateway, are recorded in provider_usage, and the dev endpoints work. */
class ProviderGatewayTests extends AbstractIntegrationTest {

    @Autowired
    private ProviderGateway gateway;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- helpers ----------

    private String loginNewUser() throws Exception {
        String email = "prov-" + UUID.randomUUID() + "@example.com";
        postJson("/api/auth/register", """
                {"email": "%s", "password": "secret123", "displayName": "Ergi"}
                """.formatted(email));
        String body = postJson("/api/auth/login", """
                {"email": "%s", "password": "secret123"}
                """.formatted(email)).body();
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private HttpResponse<byte[]> postWithToken(String path, String json, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // ---------- gateway ----------

    @Test
    void everyCallIsRecordedWithItsUserAndCost() {
        UUID userId = UUID.fromString(jdbc.queryForObject(
                "insert into users (id, email, role) values (gen_random_uuid(), ?, 'USER') returning id::text",
                String.class, "usage-" + UUID.randomUUID() + "@example.com"));

        gateway.generateText(new LlmRequest("sys", "hello", 100), CallContext.forUser(userId));
        gateway.generateImage(new ImageRequest("a cat", AspectRatio.SQUARE), CallContext.forUser(userId));

        var rows = jdbc.queryForList(
                "select kind, provider, success, cost_usd from provider_usage where user_id = ? order by created_at",
                userId);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("kind")).containsExactly("LLM", "IMAGE");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("provider")).isEqualTo("mock");
            assertThat(r.get("success")).isEqualTo(true);
        });
    }

    // ---------- dev endpoints ----------

    @Test
    void devImageEndpointReturnsAPng() throws Exception {
        String token = loginNewUser();

        HttpResponse<byte[]> response = postWithToken("/api/dev/providers/image",
                "{\"prompt\": \"Tirana skyline at night\", \"aspectRatio\": \"PORTRAIT\"}", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                v -> assertThat(v).startsWith("image/png"));
        // Every PNG file starts with the bytes 0x89 'P' 'N' 'G'.
        assertThat(Arrays.copyOfRange(response.body(), 1, 4)).isEqualTo("PNG".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void devTtsEndpointReturnsWordTimingsOrTheAudio() throws Exception {
        String token = loginNewUser();
        String json = "{\"text\": \"Hello from erkasa\", \"language\": \"EN\"}";

        HttpResponse<byte[]> info = postWithToken("/api/dev/providers/tts", json, token);
        String infoBody = new String(info.body(), StandardCharsets.UTF_8);
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(infoBody).contains("\"words\"", "\"Hello\"", "\"durationSeconds\"");

        HttpResponse<byte[]> audio = postWithToken("/api/dev/providers/tts?download=true", json, token);
        assertThat(audio.statusCode()).isEqualTo(200);
        assertThat(new String(Arrays.copyOfRange(audio.body(), 0, 4), StandardCharsets.US_ASCII)).isEqualTo("RIFF");
    }

    @Test
    void devLlmEndpointAnswers() throws Exception {
        String token = loginNewUser();

        HttpResponse<byte[]> response = postWithToken("/api/dev/providers/llm", "{\"prompt\": \"Say hi\"}", token);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("Say hi", "\"usage\"");
    }

    @Test
    void devEndpointsNeedALoggedInUser() throws Exception {
        HttpResponse<byte[]> response = postWithToken("/api/dev/providers/image", "{\"prompt\": \"x\"}", null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void invalidLanguageIsRejected() throws Exception {
        String token = loginNewUser();

        HttpResponse<byte[]> response = postWithToken("/api/dev/providers/tts",
                "{\"text\": \"hi\", \"language\": \"KLINGON\"}", token);

        assertThat(response.statusCode()).isEqualTo(400);
    }
}