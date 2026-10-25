package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.jobs.VideoOptions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 9.1: the "New video" wizard choices are validated, completed with defaults, saved and returned. */
class ProjectOptionsTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private String login() throws Exception {
        String email = "opts-" + UUID.randomUUID() + "@example.com";
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

    private HttpResponse<String> post(String body, String token) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(uri("/api/projects"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(r, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode create(String token, String body) throws Exception {
        HttpResponse<String> res = post(body, token);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        return json.readTree(res.body());
    }

    @Test
    void everyChoiceIsSavedAndReturned() throws Exception {
        String token = login();

        JsonNode created = create(token, """
                {"idea": "How CI/CD works", "language": "EN", "durationSeconds": 60, "aspectRatio": "LANDSCAPE",
                 "options": {"purpose": "BUSINESS", "template": "ANIMATED_2D", "audience": "EXPERT",
                             "tone": "ENTERTAINING", "theme": "LIGHT", "voiceSpeed": 110,
                             "captionStyle": "BOLD", "captionPosition": "TOP"}}
                """);

        JsonNode o = created.path("options");
        assertThat(o.path("purpose").asString()).isEqualTo("BUSINESS");
        assertThat(o.path("template").asString()).isEqualTo("ANIMATED_2D"); // the user's choice beats the purpose default
        assertThat(o.path("audience").asString()).isEqualTo("EXPERT");
        assertThat(o.path("tone").asString()).isEqualTo("ENTERTAINING");
        assertThat(o.path("theme").asString()).isEqualTo("LIGHT");
        assertThat(o.path("voiceSpeed").asInt()).isEqualTo(110);
        assertThat(o.path("captionStyle").asString()).isEqualTo("BOLD");
        assertThat(o.path("captionPosition").asString()).isEqualTo("TOP");

        Map<String, Object> row = jdbc.queryForMap(
                "select purpose, template, audience, tone, theme, voice_speed, caption_style, caption_position "
                        + "from projects where id = ?::uuid", created.path("id").asString());
        assertThat(row).containsEntry("purpose", "BUSINESS").containsEntry("template", "ANIMATED_2D")
                .containsEntry("audience", "EXPERT").containsEntry("tone", "ENTERTAINING")
                .containsEntry("theme", "LIGHT").containsEntry("voice_speed", 110)
                .containsEntry("caption_style", "BOLD").containsEntry("caption_position", "TOP");

        // the list returns the options too
        HttpRequest list = HttpRequest.newBuilder(uri("/api/projects"))
                .header("Authorization", "Bearer " + token).GET().build();
        JsonNode first = json.readTree(http.send(list, HttpResponse.BodyHandlers.ofString()).body()).get(0);
        assertThat(first.path("options").path("theme").asString()).isEqualTo("LIGHT");
    }

    @Test
    void templateAndToneFollowThePurpose() throws Exception {
        String token = login();

        JsonNode business = create(token, """
                {"idea": "Our sales in 2026", "language": "EN", "durationSeconds": 30, "options": {"purpose": "BUSINESS"}}
                """).path("options");
        assertThat(business.path("template").asString()).isEqualTo("INFOGRAPHIC");
        assertThat(business.path("tone").asString()).isEqualTo("PROFESSIONAL");

        JsonNode shortForm = create(token, """
                {"idea": "3 fun facts about cats", "language": "EN", "durationSeconds": 30, "options": {"purpose": "SHORT_FORM"}}
                """).path("options");
        assertThat(shortForm.path("template").asString()).isEqualTo("ANIMATED_2D");
        assertThat(shortForm.path("tone").asString()).isEqualTo("ENTERTAINING");
        assertThat(shortForm.path("voiceSpeed").asInt()).isEqualTo(VideoOptions.NORMAL_VOICE_SPEED);
        assertThat(shortForm.path("captionStyle").asString()).isEqualTo("KARAOKE");
    }

    @Test
    void withoutOptionsTheProjectIsAnExplainer() throws Exception {
        String token = login();

        JsonNode o = create(token, """
                {"idea": "3 facts about Tirana", "language": "EN", "durationSeconds": 30}
                """).path("options");

        assertThat(o.path("purpose").asString()).isEqualTo("EXPLAINER");
        assertThat(o.path("template").asString()).isEqualTo("ANIMATED_2D");
        assertThat(o.path("audience").asString()).isEqualTo("BEGINNER");
        assertThat(o.path("tone").asString()).isEqualTo("EDUCATIONAL");
        assertThat(o.path("theme").asString()).isEqualTo("DARK");
        assertThat(o.path("captionPosition").asString()).isEqualTo("BOTTOM");
    }

    @Test
    void badChoicesAreRejected() throws Exception {
        String token = login();

        HttpResponse<String> tooFast = post("""
                {"idea": "x", "language": "EN", "durationSeconds": 30, "options": {"voiceSpeed": 200}}
                """, token);
        assertThat(tooFast.statusCode()).isEqualTo(400);
        assertThat(tooFast.body()).contains("VALIDATION_FAILED", "voiceSpeed");

        HttpResponse<String> unknownTemplate = post("""
                {"idea": "x", "language": "EN", "durationSeconds": 30, "options": {"template": "HOLOGRAM"}}
                """, token);
        assertThat(unknownTemplate.statusCode()).isEqualTo(400);
    }
}