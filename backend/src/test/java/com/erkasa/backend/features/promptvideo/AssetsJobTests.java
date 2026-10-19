package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * 4.3: approving the script creates an image and a voice for every scene, in parallel,
 * stores them, and moves the project to ASSETS_READY. Mock providers: free and fast.
 *
 * The image provider is a spy so one test can make a single scene fail.
 */
class AssetsJobTests extends AbstractIntegrationTest {

    @MockitoSpyBean
    private MockImageProvider images;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- helpers ----------

    private String login() throws Exception {
        String email = "assets-" + UUID.randomUUID() + "@example.com";
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

    private HttpResponse<byte[]> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(r.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode read(HttpResponse<byte[]> res) {
        return json.readTree(new String(res.body(), StandardCharsets.UTF_8));
    }

    private JsonNode waitFor(String token, String id, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        JsonNode p = null;
        while (System.currentTimeMillis() < deadline) {
            p = read(call("GET", "/api/projects/" + id, null, token));
            if (status.equals(p.path("status").asString())) {
                return p;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Project " + id + " never reached " + status + ", last: " + p);
    }

    /** Creates a project with this idea, waits for the script, approves it. Returns the project id. */
    private String approvedProject(String token, String idea) throws Exception {
        HttpResponse<byte[]> created = call("POST", "/api/projects", """
                {"idea": "%s", "language": "EN", "durationSeconds": 30}
                """.formatted(idea), token);
        assertThat(created.statusCode()).isEqualTo(202);
        String id = read(created).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");
        assertThat(call("POST", "/api/projects/" + id + "/approve", null, token).statusCode()).isEqualTo(200);
        return id;
    }

    // ---------- tests ----------

    @Test
    void approveCreatesAnImageAndAVoiceForEveryScene() throws Exception {
        String token = login();
        String id = approvedProject(token, "3 facts about Tirana");

        JsonNode ready = waitFor(token, id, "ASSETS_READY");
        int scenes = ready.path("script").path("scenes").size();
        JsonNode jobs = ready.path("jobs");
        assertThat(jobs.size()).isEqualTo(2);
        assertThat(jobs.get(0).path("kind").asString() + "=" + jobs.get(0).path("status").asString())
                .isEqualTo("SCRIPT=SUCCEEDED");
        assertThat(jobs.get(1).path("kind").asString() + "=" + jobs.get(1).path("status").asString())
                .isEqualTo("ASSETS=SUCCEEDED");

        JsonNode assets = read(call("GET", "/api/projects/" + id + "/assets", null, token));
        assertThat(assets.path("total").asInt()).isEqualTo(scenes * 2);
        assertThat(assets.path("done").asInt()).isEqualTo(scenes * 2);
        assertThat(assets.path("scenes").size()).isEqualTo(scenes);

        JsonNode first = assets.path("scenes").get(0);
        assertThat(first.path("image").path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(first.path("image").path("meta").path("width").asInt()).isEqualTo(720);   // mock PORTRAIT
        assertThat(first.path("image").path("meta").path("height").asInt()).isEqualTo(1280);
        assertThat(first.path("voice").path("meta").path("durationSeconds").asDouble()).isPositive();
        assertThat(first.path("voice").path("meta").path("words").size()).isPositive();

        // the files are really in storage and can be downloaded
        HttpResponse<byte[]> png = call("GET", first.path("image").path("url").asString(), null, token);
        assertThat(png.statusCode()).isEqualTo(200);
        assertThat(png.headers().firstValue("Content-Type")).hasValue("image/png");
        assertThat(new String(png.body(), 1, 3, StandardCharsets.US_ASCII)).isEqualTo("PNG");

        HttpResponse<byte[]> wav = call("GET", first.path("voice").path("url").asString(), null, token);
        assertThat(wav.statusCode()).isEqualTo(200);
        assertThat(new String(wav.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");

        // every provider call is linked to the ASSETS job
        String jobId = ready.path("jobs").get(1).path("id").asString();
        Integer imageCalls = jdbc.queryForObject(
                "select count(*) from provider_usage where kind = 'IMAGE' and job_id = ?::uuid", Integer.class, jobId);
        Integer voiceCalls = jdbc.queryForObject(
                "select count(*) from provider_usage where kind = 'TTS' and job_id = ?::uuid", Integer.class, jobId);
        assertThat(imageCalls).isEqualTo(scenes);
        assertThat(voiceCalls).isEqualTo(scenes);
    }

    @Test
    void oneFailedSceneFailsTheProjectButKeepsTheOthers() throws Exception {
        // Scene 2's image prompt (index 1) is "Mock scene 2 illustrating: ..." -> refuse it.
        doThrow(new ProviderException("mock", "NO_IMAGE", "blocked by safety filter", false, null))
                .when(images).generate(argThat(r -> r != null && r.prompt().startsWith("Mock scene 2 ")
                        && r.prompt().contains("unlucky idea")));
        String token = login();
        String id = approvedProject(token, "unlucky idea");

        JsonNode failed = waitFor(token, id, "FAILED");
        assertThat(failed.path("errorCode").asString()).isEqualTo("NO_IMAGE");
        assertThat(failed.path("errorMessage").asString()).contains("IMAGE scene 2");

        JsonNode assets = read(call("GET", "/api/projects/" + id + "/assets", null, token));
        int total = assets.path("total").asInt();
        assertThat(assets.path("done").asInt()).isEqualTo(total - 1);
        JsonNode scene2 = assets.path("scenes").get(1);
        assertThat(scene2.path("image").path("status").asString()).isEqualTo("FAILED");
        JsonNode url = scene2.path("image").path("url");
        assertThat(url.isNull() || url.isMissingNode()).as("no download link for a failed image").isTrue();
        assertThat(scene2.path("voice").path("status").asString()).isEqualTo("SUCCEEDED");

        assertThat(call("GET", "/api/projects/" + id + "/assets/1/image", null, token).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/projects/" + id + "/assets/0/image", null, token).statusCode()).isEqualTo(200);
    }

    @Test
    void motionGraphicScenesGetAVoiceButNoImage() throws Exception {
        String token = login();
        HttpResponse<byte[]> created = call("POST", "/api/projects", """
                {"idea": "how CI/CD works", "language": "EN", "durationSeconds": 30}
                """, token);
        String id = read(created).path("id").asString();
        JsonNode review = waitFor(token, id, "SCRIPT_REVIEW");
        JsonNode mockScenes = review.path("script").path("scenes");

        // keep the mock narration (right length), but make every scene except the last a graphic
        StringBuilder scenes = new StringBuilder();
        for (int i = 0; i < mockScenes.size(); i++) {
            String narration = json.writeValueAsString(mockScenes.get(i).path("narration").asString());
            String visual = i == mockScenes.size() - 1
                    ? "{\"type\":\"image\"}"
                    : "{\"type\":\"flow\",\"items\":[\"Code\",\"Build\",\"Test\",\"Deploy\"]}";
            String prompt = i == mockScenes.size() - 1 ? "Mock scene image" : "";
            if (i > 0) scenes.append(',');
            scenes.append("{\"narration\":").append(narration)
                    .append(",\"onScreenText\":\"\",\"imagePrompt\":\"").append(prompt)
                    .append("\",\"visual\":").append(visual).append('}');
        }
        HttpResponse<byte[]> edit = call("PUT", "/api/projects/" + id + "/script",
                "{\"script\":{\"title\":\"CI/CD\",\"musicMood\":\"upbeat\",\"scenes\":[" + scenes + "]}}", token);
        assertThat(edit.statusCode()).as(new String(edit.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(call("POST", "/api/projects/" + id + "/approve", null, token).statusCode()).isEqualTo(200);

        JsonNode ready = waitFor(token, id, "ASSETS_READY");
        String jobId = ready.path("jobs").get(1).path("id").asString();
        int n = mockScenes.size();
        // only the last scene has an image; every scene has a voice
        assertThat(jdbc.queryForObject("select count(*) from job_steps where job_id = ?::uuid and type = 'IMAGE'",
                Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from provider_usage where kind = 'IMAGE' and job_id = ?::uuid",
                Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from provider_usage where kind = 'TTS' and job_id = ?::uuid",
                Integer.class, jobId)).isEqualTo(n);

        JsonNode assets = read(call("GET", "/api/projects/" + id + "/assets", null, token));
        assertThat(assets.path("total").asInt()).isEqualTo(n + 1);
        assertThat(assets.path("done").asInt()).isEqualTo(n + 1);
        JsonNode first = assets.path("scenes").get(0);
        assertThat(first.path("image").isNull() || first.path("image").isMissingNode()).isTrue();
        assertThat(first.path("voice").path("status").asString()).isEqualTo("SUCCEEDED");
        // the visual is stored with the script, for the render worker
        assertThat(ready.path("script").path("scenes").get(0).path("visual").path("type").asString()).isEqualTo("flow");

        // redoing the image of a graphic scene makes no sense
        HttpResponse<byte[]> redo = call("POST", "/api/projects/" + id + "/scenes/0/redo", "{\"image\":true}", token);
        assertThat(redo.statusCode()).isEqualTo(400);
        assertThat(new String(redo.body(), StandardCharsets.UTF_8)).contains("NO_IMAGE_IN_SCENE");
    }

    @Test
    void approvingAgainDoesNotStartASecondAssetsJob() throws Exception {
        String token = login();
        String id = approvedProject(token, "approve twice");
        waitFor(token, id, "ASSETS_READY");

        assertThat(call("POST", "/api/projects/" + id + "/approve", null, token).statusCode()).isEqualTo(409);
        assertThat(read(call("GET", "/api/projects/" + id, null, token)).path("jobs").size()).isEqualTo(2);
    }

    @Test
    void othersCannotSeeTheFiles() throws Exception {
        String owner = login();
        String id = approvedProject(owner, "private files");
        waitFor(owner, id, "ASSETS_READY");
        String stranger = login();

        assertThat(call("GET", "/api/projects/" + id + "/assets", null, stranger).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/projects/" + id + "/assets/0/image", null, stranger).statusCode()).isEqualTo(404);
    }

    @Test
    void beforeApprovalThereAreNoAssets() throws Exception {
        String token = login();
        HttpResponse<byte[]> created = call("POST", "/api/projects", """
                {"idea": "not yet", "language": "EN", "durationSeconds": 30}
                """, token);
        String id = read(created).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");

        JsonNode assets = read(call("GET", "/api/projects/" + id + "/assets", null, token));
        assertThat(assets.path("total").asInt()).isZero();
        assertThat(call("GET", "/api/projects/" + id + "/assets/0/image", null, token).statusCode()).isEqualTo(404);
    }
}
