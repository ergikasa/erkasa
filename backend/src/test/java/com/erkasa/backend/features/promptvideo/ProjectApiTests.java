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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 4.2: create -> script written in the background -> review (edit / regenerate) -> approve, or cancel.
 * Uses the mock LLM, so it is free and fast.
 */
class ProjectApiTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    // ---------- helpers ----------

    private String login() throws Exception {
        String email = "proj-" + UUID.randomUUID() + "@example.com";
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

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode create(String token, String body) throws Exception {
        HttpResponse<String> res = call("POST", "/api/projects", body, token);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        return json.readTree(res.body());
    }

    private JsonNode createDefault(String token) throws Exception {
        return create(token, """
                {"idea": "3 facts about Tirana", "language": "EN", "durationSeconds": 30}
                """);
    }

    /** Polls GET /api/projects/{id} until it reaches the status, at most 15 seconds. */
    private JsonNode waitFor(String token, String id, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode p = null;
        while (System.currentTimeMillis() < deadline) {
            p = json.readTree(call("GET", "/api/projects/" + id, null, token).body());
            if (status.equals(p.path("status").asString())) {
                return p;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Project " + id + " never reached " + status + ", last: " + p);
    }

    // ---------- tests ----------

    @Test
    void createWritesTheScriptInTheBackground() throws Exception {
        String token = login();

        JsonNode created = createDefault(token);
        String id = created.path("id").asString();
        assertThat(created.path("status").asString()).isIn("SCRIPT_PENDING", "SCRIPT_REVIEW");
        assertThat(created.path("aspectRatio").asString()).isEqualTo("PORTRAIT");
        assertThat(created.path("estimatedCredits").asInt()).isEqualTo(30);

        JsonNode review = waitFor(token, id, "SCRIPT_REVIEW");
        assertThat(review.path("title").asString()).contains("3 facts about Tirana");
        assertThat(review.path("script").path("scenes").size()).isGreaterThanOrEqualTo(3);
        assertThat(review.path("jobs").size()).isEqualTo(1);
        assertThat(review.path("jobs").get(0).path("kind").asString()).isEqualTo("SCRIPT");
        assertThat(review.path("jobs").get(0).path("status").asString()).isEqualTo("SUCCEEDED");

        // the LLM call is recorded under the job id, so its cost is linked to this project
        String jobId = review.path("jobs").get(0).path("id").asString();
        Integer calls = jdbc.queryForObject(
                "select count(*) from provider_usage where kind = 'LLM' and job_id = ?::uuid", Integer.class, jobId);
        assertThat(calls).isEqualTo(1);
        String step = jdbc.queryForObject(
                "select status from job_steps where job_id = ?::uuid and type = 'SCRIPT'", String.class, jobId);
        assertThat(step).isEqualTo("SUCCEEDED");
    }

    @Test
    void editedScriptIsSavedWhenValidAndRefusedWhenNot() throws Exception {
        String token = login();
        String id = createDefault(token).path("id").asString();
        JsonNode script = waitFor(token, id, "SCRIPT_REVIEW").path("script");

        ObjectNode edited = (ObjectNode) script.deepCopy();
        edited.put("title", "My own title");
        ((ObjectNode) edited.path("scenes").get(0)).put("onScreenText", "Edited by Ergi");
        HttpResponse<String> ok = call("PUT", "/api/projects/" + id + "/script",
                json.writeValueAsString(Map.of("script", edited)), token);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        JsonNode saved = json.readTree(ok.body());
        assertThat(saved.path("title").asString()).isEqualTo("My own title");
        assertThat(saved.path("script").path("scenes").get(0).path("onScreenText").asString()).isEqualTo("Edited by Ergi");

        ObjectNode broken = (ObjectNode) edited.deepCopy();
        ArrayNode oneScene = json.createArrayNode().add(edited.path("scenes").get(0));
        broken.set("scenes", oneScene);
        HttpResponse<String> bad = call("PUT", "/api/projects/" + id + "/script",
                json.writeValueAsString(Map.of("script", broken)), token);
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(bad.body()).contains("\"code\":\"INVALID_SCRIPT\"", "\"problems\"", "scenes");
    }

    @Test
    void approveMovesToAssetsPendingOnlyOnce() throws Exception {
        String token = login();
        String id = createDefault(token).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");

        HttpResponse<String> first = call("POST", "/api/projects/" + id + "/approve", null, token);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(json.readTree(first.body()).path("status").asString()).isEqualTo("ASSETS_PENDING");

        HttpResponse<String> second = call("POST", "/api/projects/" + id + "/approve", null, token);
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("\"code\":\"INVALID_STATE\"", "ASSETS_PENDING");

        // after approval the script can no longer be edited
        HttpResponse<String> edit = call("PUT", "/api/projects/" + id + "/script",
                "{\"script\":{\"title\":\"x\",\"musicMood\":\"calm\",\"scenes\":[]}}", token);
        assertThat(edit.statusCode()).isEqualTo(409);
    }

    @Test
    void regenerateWritesASecondScript() throws Exception {
        String token = login();
        String id = createDefault(token).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");

        HttpResponse<String> res = call("POST", "/api/projects/" + id + "/regenerate-script", null, token);
        assertThat(res.statusCode()).isEqualTo(202);

        JsonNode again = waitFor(token, id, "SCRIPT_REVIEW");
        assertThat(again.path("jobs").size()).isEqualTo(2);
        assertThat(again.path("jobs").get(1).path("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void cancelIsFinal() throws Exception {
        String token = login();
        String id = createDefault(token).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");

        HttpResponse<String> cancel = call("POST", "/api/projects/" + id + "/cancel", null, token);
        assertThat(json.readTree(cancel.body()).path("status").asString()).isEqualTo("CANCELLED");

        assertThat(call("POST", "/api/projects/" + id + "/approve", null, token).statusCode()).isEqualTo(409);
        assertThat(call("POST", "/api/projects/" + id + "/regenerate-script", null, token).statusCode()).isEqualTo(409);
    }

    @Test
    void otherUsersGetNotFound() throws Exception {
        String owner = login();
        String id = createDefault(owner).path("id").asString();
        String stranger = login();

        HttpResponse<String> get = call("GET", "/api/projects/" + id, null, stranger);
        assertThat(get.statusCode()).isEqualTo(404);
        assertThat(get.body()).contains("PROJECT_NOT_FOUND");
        assertThat(call("POST", "/api/projects/" + id + "/cancel", null, stranger).statusCode()).isEqualTo(404);
    }

    @Test
    void listShowsOnlyMyProjectsNewestFirst() throws Exception {
        String token = login();
        String first = createDefault(token).path("id").asString();
        Thread.sleep(5);
        String second = createDefault(token).path("id").asString();
        createDefault(login()); // someone else's

        JsonNode list = json.readTree(call("GET", "/api/projects", null, token).body());

        assertThat(list.size()).isEqualTo(2);
        assertThat(list.get(0).path("id").asString()).isEqualTo(second);
        assertThat(list.get(1).path("id").asString()).isEqualTo(first);
    }

    @Test
    void badInputIsRejected() throws Exception {
        String token = login();

        HttpResponse<String> tooLong = call("POST", "/api/projects", """
                {"idea": "x", "language": "EN", "durationSeconds": 120}
                """, token);
        assertThat(tooLong.statusCode()).isEqualTo(400);
        assertThat(tooLong.body()).contains("VALIDATION_FAILED", "durationSeconds");

        HttpResponse<String> noIdea = call("POST", "/api/projects", """
                {"idea": " ", "language": "EN", "durationSeconds": 30}
                """, token);
        assertThat(noIdea.statusCode()).isEqualTo(400);

        // an Albanian voice for an English video
        HttpResponse<String> wrongVoice = call("POST", "/api/projects", """
                {"idea": "x", "language": "EN", "durationSeconds": 30, "voiceId": "sq-AL-IlirNeural"}
                """, token);
        assertThat(wrongVoice.statusCode()).isEqualTo(400);
        assertThat(wrongVoice.body()).contains("UNKNOWN_VOICE");
    }

    @Test
    void aPickedVoiceIsKept() throws Exception {
        String token = login();

        JsonNode created = create(token, """
                {"idea": "Mirëdita Tirana", "language": "SQ", "durationSeconds": 30,
                 "aspectRatio": "LANDSCAPE", "voiceId": "sq-AL-AnilaNeural"}
                """);

        assertThat(created.path("voiceId").asString()).isEqualTo("sq-AL-AnilaNeural");
        assertThat(created.path("aspectRatio").asString()).isEqualTo("LANDSCAPE");
    }

    @Test
    void needsLogin() throws Exception {
        assertThat(get("/api/projects").statusCode()).isEqualTo(401);
    }
}