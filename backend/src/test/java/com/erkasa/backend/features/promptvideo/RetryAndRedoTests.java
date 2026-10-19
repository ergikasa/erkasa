package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.providers.ProviderException;
import com.erkasa.backend.providers.image.MockImageProvider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 4.4: "try again" redoes only what failed, and one scene can be redone without paying for
 * the others again. Mock providers; the image provider is a spy so scenes can be made to fail.
 */
class RetryAndRedoTests extends AbstractIntegrationTest {

    @MockitoSpyBean
    private MockImageProvider images;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- helpers ----------

    private String login() throws Exception {
        String email = "retry-" + UUID.randomUUID() + "@example.com";
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

    private JsonNode get(String token, String id) throws Exception {
        return json.readTree(call("GET", "/api/projects/" + id, null, token).body());
    }

    private JsonNode waitFor(String token, String id, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode p = null;
        while (System.currentTimeMillis() < deadline) {
            p = get(token, id);
            if (status.equals(p.path("status").asString())) {
                return p;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Project " + id + " never reached " + status + ", last: " + p);
    }

    private String approved(String token, String idea) throws Exception {
        String id = json.readTree(call("POST", "/api/projects", """
                {"idea": "%s", "language": "EN", "durationSeconds": 30}
                """.formatted(idea), token).body()).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");
        assertThat(call("POST", "/api/projects/" + id + "/approve", null, token).statusCode()).isEqualTo(200);
        return id;
    }

    /** Makes scene 2's image (index 1) fail for projects with this idea: once, or always. */
    private void failScene2(String idea, boolean always) {
        ProviderException blocked = new ProviderException("mock", "NO_IMAGE", "blocked", false, null);
        var stub = always ? doThrow(blocked) : doThrow(blocked).doCallRealMethod();
        stub.when(images).generate(argThat(r -> r != null
                && r.prompt().startsWith("Mock scene 2 ") && r.prompt().contains(idea)));
    }

    private int providerCalls(String kind, String jobId) {
        return jdbc.queryForObject("select count(*) from provider_usage where kind = ? and job_id = ?::uuid",
                Integer.class, kind, jobId);
    }

    private int attempts(String jobId, String type, int scene) {
        return jdbc.queryForObject(
                "select attempts from job_steps where job_id = ?::uuid and type = ? and scene_index = ?",
                Integer.class, jobId, type, scene);
    }

    // ---------- retry ----------

    @Test
    void retryRedoesOnlyTheFailedStep() throws Exception {
        failScene2("retry once idea", false);
        String token = login();
        String id = approved(token, "retry once idea");
        JsonNode failed = waitFor(token, id, "FAILED");
        String jobId = failed.path("jobs").get(1).path("id").asString();
        int scenes = failed.path("script").path("scenes").size();
        assertThat(providerCalls("IMAGE", jobId)).isEqualTo(scenes);   // includes the failed call

        HttpResponse<String> retry = call("POST", "/api/projects/" + id + "/retry", null, token);
        assertThat(retry.statusCode()).isEqualTo(202);
        JsonNode ready = waitFor(token, id, "ASSETS_READY");

        // the same assets job was reused: no new job, only ONE more image call, no voice calls
        assertThat(ready.path("jobs").size()).isEqualTo(2);
        assertThat(ready.path("jobs").get(1).path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(providerCalls("IMAGE", jobId)).isEqualTo(scenes + 1);
        assertThat(providerCalls("TTS", jobId)).isEqualTo(scenes);
        assertThat(attempts(jobId, "IMAGE", 1)).isEqualTo(2);
        assertThat(attempts(jobId, "IMAGE", 0)).isEqualTo(1);
        assertThat(ready.path("errorCode").isNull() || ready.path("errorCode").isMissingNode()).isTrue();
    }

    @Test
    void retryStopsAfterThreeFailuresOfTheSameScene() throws Exception {
        failScene2("hopeless idea", true);
        String token = login();
        String id = approved(token, "hopeless idea");

        waitFor(token, id, "FAILED");                      // attempt 1
        assertThat(call("POST", "/api/projects/" + id + "/retry", null, token).statusCode()).isEqualTo(202);
        waitFor(token, id, "FAILED");                      // attempt 2
        assertThat(call("POST", "/api/projects/" + id + "/retry", null, token).statusCode()).isEqualTo(202);
        waitFor(token, id, "FAILED");                      // attempt 3

        HttpResponse<String> fourth = call("POST", "/api/projects/" + id + "/retry", null, token);
        assertThat(fourth.statusCode()).isEqualTo(429);
        assertThat(fourth.body()).contains("TOO_MANY_RETRIES", "scene 2");
    }

    @Test
    void retryOnlyWorksOnAFailedProject() throws Exception {
        String token = login();
        String id = approved(token, "all good");
        waitFor(token, id, "ASSETS_READY");

        assertThat(call("POST", "/api/projects/" + id + "/retry", null, token).statusCode()).isEqualTo(409);
    }

    // ---------- redo one scene ----------

    @Test
    void redoOneImageReusesEverythingElse() throws Exception {
        String token = login();
        String id = approved(token, "redo one image");
        JsonNode before = waitFor(token, id, "ASSETS_READY");
        int scenes = before.path("script").path("scenes").size();

        HttpResponse<String> redo = call("POST", "/api/projects/" + id + "/scenes/0/redo",
                "{\"image\": true}", token);
        assertThat(redo.statusCode()).as(redo.body()).isEqualTo(202);
        JsonNode after = waitFor(token, id, "ASSETS_READY");

        assertThat(after.path("jobs").size()).isEqualTo(3);
        String newJob = after.path("jobs").get(2).path("id").asString();
        assertThat(providerCalls("IMAGE", newJob)).isEqualTo(1);
        assertThat(providerCalls("TTS", newJob)).isZero();
        assertThat(attempts(newJob, "IMAGE", 0)).isEqualTo(1);
        assertThat(attempts(newJob, "VOICE", 0)).isZero();   // reused, not generated

        // the new job has the full set of files, so the asset links still work for every scene
        JsonNode assets = json.readTree(call("GET", "/api/projects/" + id + "/assets", null, token).body());
        assertThat(assets.path("done").asInt()).isEqualTo(scenes * 2);
        String newKey = jdbc.queryForObject(
                "select output_key from job_steps where job_id = ?::uuid and type = 'IMAGE' and scene_index = 0",
                String.class, newJob);
        assertThat(newKey).contains(newJob);
        assertThat(call("GET", "/api/projects/" + id + "/assets/0/image", null, token).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/projects/" + id + "/assets/1/voice", null, token).statusCode()).isEqualTo(200);
    }

    @Test
    void redoWithNewNarrationChangesTheScriptAndTheVoice() throws Exception {
        String token = login();
        String id = approved(token, "new narration");
        JsonNode before = waitFor(token, id, "ASSETS_READY");
        String old = before.path("script").path("scenes").get(1).path("narration").asString();
        String changed = old.replace("lorem", "Tirana");   // same length, so the script stays valid

        HttpResponse<String> redo = call("POST", "/api/projects/" + id + "/scenes/1/redo",
                json.writeValueAsString(Map.of("narration", changed)), token);
        assertThat(redo.statusCode()).as(redo.body()).isEqualTo(202);
        JsonNode after = waitFor(token, id, "ASSETS_READY");

        assertThat(after.path("script").path("scenes").get(1).path("narration").asString()).isEqualTo(changed);
        String newJob = after.path("jobs").get(2).path("id").asString();
        assertThat(providerCalls("TTS", newJob)).isEqualTo(1);
        assertThat(providerCalls("IMAGE", newJob)).isZero();
    }

    @Test
    void badRedoRequestsAreRefused() throws Exception {
        String token = login();
        String id = approved(token, "bad redo");

        // still generating? -> 409 (or already ready -> other checks below)
        JsonNode ready = waitFor(token, id, "ASSETS_READY");
        int scenes = ready.path("script").path("scenes").size();

        assertThat(call("POST", "/api/projects/" + id + "/scenes/0/redo", "{}", token).statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/projects/" + id + "/scenes/" + scenes + "/redo", "{\"image\":true}", token)
                .statusCode()).isEqualTo(404);

        String tooLong = "word ".repeat(190);
        HttpResponse<String> invalid = call("POST", "/api/projects/" + id + "/scenes/0/redo",
                json.writeValueAsString(Map.of("narration", tooLong)), token);
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("INVALID_SCRIPT");

        // nothing changed by the refused requests
        assertThat(get(token, id).path("jobs").size()).isEqualTo(2);
    }

    @Test
    void redoIsNotPossibleBeforeTheFilesAreReady() throws Exception {
        String token = login();
        String id = json.readTree(call("POST", "/api/projects", """
                {"idea": "too early", "language": "EN", "durationSeconds": 30}
                """, token).body()).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");

        assertThat(call("POST", "/api/projects/" + id + "/scenes/0/redo", "{\"image\":true}", token).statusCode())
                .isEqualTo(409);
    }
}