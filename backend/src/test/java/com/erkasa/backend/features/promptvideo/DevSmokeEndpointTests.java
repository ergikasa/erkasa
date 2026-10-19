package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;

/**
 * Sub-task 3.5 with the mock providers (free): one call goes through LLM, image and TTS,
 * writes the files, and every provider call is recorded under the same job id.
 * The same endpoint with real keys in infra/.env is the manual "real providers" check.
 */
class DevSmokeEndpointTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String login() throws Exception {
        String email = "smoke-" + UUID.randomUUID() + "@example.com";
        postJson("/api/auth/register", """
                {"email": "%s", "password": "secret123", "displayName": "Ergi"}
                """.formatted(email));
        String body = postJson("/api/auth/login", """
                {"email": "%s", "password": "secret123"}
                """.formatted(email)).body();
        Matcher token = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        assertThat(token.find()).isTrue();
        return token.group(1);
    }

    private HttpResponse<String> smoke(String json, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/dev/smoke"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String body, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as(name).isTrue();
        return m.group(1).replace("\\\\", "\\");
    }

    @Test
    void runsAllThreeProvidersRecordsCostAndWritesFiles() throws Exception {
        HttpResponse<String> response = smoke("""
                {"idea": "3 facts about Tirana", "language": "EN", "durationSeconds": 30, "scenes": 2}
                """, login());

        assertThat(response.statusCode()).isEqualTo(200);
        String jobId = field(response.body(), "jobId");
        Path dir = Path.of(field(response.body(), "outputDir"));
        try {
            // one LLM call + 2 images + 2 voices, all under the same job id
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select kind, count(*) as n from provider_usage where job_id = ?::uuid group by kind order by kind",
                    jobId);
            assertThat(rows).extracting(r -> r.get("kind") + "=" + r.get("n"))
                    .containsExactly("IMAGE=2", "LLM=1", "TTS=2");

            assertThat(response.body()).contains("\"totalCostUsd\"", "\"costs\"", "\"rendered\"");
            assertThat(dir.resolve("script.json")).exists();
            assertThat(dir.resolve("summary.json")).exists();
            assertThat(dir.resolve("scene-1.png")).exists();
            assertThat(dir.resolve("scene-1.wav")).exists();
            assertThat(dir.resolve("scene-1.words.json")).exists();
            assertThat(dir.resolve("scene-2.png")).exists();
            assertThat(dir.resolve("scene-3.png")).doesNotExist();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void needsLogin() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/dev/smoke"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"idea\":\"x\",\"language\":\"EN\",\"durationSeconds\":30}"))
                .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void tooManyScenesIsRejected() throws Exception {
        HttpResponse<String> response = smoke("""
                {"idea": "x", "language": "EN", "durationSeconds": 30, "scenes": 50}
                """, login());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}