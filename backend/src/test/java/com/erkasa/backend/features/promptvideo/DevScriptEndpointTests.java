package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;

/**
 * The whole chain with the mock LLM: HTTP -> ScriptGenerator -> ProviderGateway -> MockLlmProvider
 * -> JSON -> validation -> provider_usage row.
 */
class DevScriptEndpointTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String[] loginNewUser() throws Exception {
        String email = "script-" + UUID.randomUUID() + "@example.com";
        postJson("/api/auth/register", """
                {"email": "%s", "password": "secret123", "displayName": "Ergi"}
                """.formatted(email));
        String body = postJson("/api/auth/login", """
                {"email": "%s", "password": "secret123"}
                """.formatted(email)).body();
        Matcher token = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        Matcher id = Pattern.compile("\"id\":\"([^\"]+)\"").matcher(body);
        assertThat(token.find() && id.find()).isTrue();
        return new String[] {token.group(1), id.group(1)};
    }

    private HttpResponse<String> generate(String json, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/dev/script"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void generatesAValidScriptAndRecordsTheCall() throws Exception {
        String[] user = loginNewUser();

        HttpResponse<String> response = generate("""
                {"idea": "5 facts about Tirana", "language": "SQ", "durationSeconds": 45}
                """, user[0]);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"title\"", "\"musicMood\"", "\"scenes\"",
                "\"narration\"", "\"onScreenText\"", "\"imagePrompt\"", "5 facts about Tirana");

        Integer llmCalls = jdbc.queryForObject(
                "select count(*) from provider_usage where kind = 'LLM' and user_id = ?::uuid",
                Integer.class, user[1]);
        assertThat(llmCalls).isEqualTo(1);
    }

    @Test
    void durationOutsideFifteenToNinetySecondsIsRejected() throws Exception {
        String token = loginNewUser()[0];

        HttpResponse<String> tooLong = generate("""
                {"idea": "x", "language": "EN", "durationSeconds": 120}
                """, token);

        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(tooLong.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"durationSeconds\"");
    }
}