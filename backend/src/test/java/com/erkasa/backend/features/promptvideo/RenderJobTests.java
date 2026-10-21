package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.features.promptvideo.render.RenderQueues;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 5.2b: POST /render sends the spec to RabbitMQ, the worker's answer moves the project to DONE,
 * and the MP4 can be downloaded. This test plays the worker: it reads the request from the
 * queue, uploads a fake MP4 through the presigned link and sends the result back.
 */
class RenderJobTests extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private ObjectMapper json;

    // ---------- helpers ----------

    private String login() throws Exception {
        String email = "render-" + UUID.randomUUID() + "@example.com";
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

    /** A project with all images and voices ready. */
    private String readyProject(String token, String idea) throws Exception {
        String id = read(call("POST", "/api/projects", """
                {"idea": "%s", "language": "EN", "durationSeconds": 30}
                """.formatted(idea), token)).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");
        assertThat(call("POST", "/api/projects/" + id + "/approve", null, token).statusCode()).isEqualTo(200);
        waitFor(token, id, "ASSETS_READY");
        return id;
    }

    /** Reads render requests until the one for this project arrives (other tests may leave some). */
    private JsonNode takeRequest(String projectId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Message m = rabbit.receive(RenderQueues.REQUESTS, 1000);
            if (m == null) {
                continue;
            }
            JsonNode req = json.readTree(new String(m.getBody(), StandardCharsets.UTF_8));
            if (projectId.equals(req.path("projectId").asString())) {
                return req;
            }
        }
        throw new AssertionError("No render request for project " + projectId);
    }

    private void reply(String body) {
        rabbit.send("", RenderQueues.RESULTS, MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build());
    }

    // ---------- tests ----------

    @Test
    void renderSendsTheSpecAndTheWorkersResultFinishesTheProject() throws Exception {
        String token = login();
        String id = readyProject(token, "render me");
        int scenes = read(call("GET", "/api/projects/" + id, null, token)).path("script").path("scenes").size();

        HttpResponse<byte[]> start = call("POST", "/api/projects/" + id + "/render", null, token);
        assertThat(start.statusCode()).isEqualTo(202);
        assertThat(read(start).path("status").asString()).isEqualTo("RENDERING");

        // --- the worker's side ---
        JsonNode req = takeRequest(id);
        String jobId = req.path("jobId").asString();
        JsonNode spec = req.path("spec");
        assertThat(spec.path("aspectRatio").asString()).isEqualTo("PORTRAIT");
        assertThat(spec.path("fps").asInt()).isEqualTo(30);
        // the mock script's mood; the worker turns it into a track from music/inspiring/
        assertThat(spec.path("music").asString()).isEqualTo("mood:inspiring");
        // 5.6: music at half the 5.4 level, and the LLM's sound effects go to the worker
        assertThat(spec.path("musicVolume").asDouble()).isEqualTo(0.06);
        assertThat(spec.path("scenes").size()).isEqualTo(scenes);
        JsonNode first = spec.path("scenes").get(0);
        assertThat(first.path("image").asString()).startsWith("http").contains("X-Amz-Signature");
        assertThat(first.path("audio").asString()).startsWith("http").contains("X-Amz-Signature");
        assertThat(first.path("audioSeconds").asDouble()).isPositive();
        assertThat(first.path("words").size()).isPositive();
        assertThat(first.path("sfx").get(0).path("sound").asString()).isEqualTo("ding");
        assertThat(first.path("sfx").get(0).path("onWord").asString()).isEqualTo("mock");
        assertThat(spec.path("scenes").get(1).path("sfx").size()).isZero();
        assertThat(req.path("outputKey").asString()).isEqualTo("projects/" + id + "/" + jobId + "/video.mp4");

        // the voice link really works without credentials
        HttpResponse<byte[]> voice = http.send(HttpRequest.newBuilder(URI.create(first.path("audio").asString())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(voice.statusCode()).isEqualTo(200);
        assertThat(new String(voice.body(), 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");

        // upload the "MP4" through the presigned link, like the worker does
        byte[] fakeMp4 = "fake-mp4-bytes".getBytes(StandardCharsets.US_ASCII);
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(req.path("outputUrl").asString()))
                .header("Content-Type", "video/mp4")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fakeMp4)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);

        reply("{\"jobId\":\"" + jobId + "\",\"type\":\"PROGRESS\",\"percent\":50}");
        reply("{\"jobId\":\"" + jobId + "\",\"type\":\"SUCCEEDED\",\"percent\":100,\"durationSeconds\":31.5,\"bytes\":14,\"renderSeconds\":42.0}");
        // --- end of the worker's side ---

        JsonNode done = waitFor(token, id, "DONE");
        assertThat(done.path("jobs").get(2).path("kind").asString()).isEqualTo("RENDER");
        assertThat(done.path("jobs").get(2).path("status").asString()).isEqualTo("SUCCEEDED");

        HttpResponse<byte[]> video = call("GET", "/api/projects/" + id + "/video?download=true", null, token);
        assertThat(video.statusCode()).isEqualTo(200);
        assertThat(video.headers().firstValue("Content-Type")).hasValue("video/mp4");
        assertThat(video.headers().firstValue("Content-Disposition").orElse("")).contains("attachment");
        assertThat(video.body()).isEqualTo(fakeMp4);
    }

    @Test
    void failedRenderCanBeRetried() throws Exception {
        String token = login();
        String id = readyProject(token, "render fails once");
        call("POST", "/api/projects/" + id + "/render", null, token);
        String jobId = takeRequest(id).path("jobId").asString();

        reply("{\"jobId\":\"" + jobId + "\",\"type\":\"FAILED\",\"error\":\"Chrome crashed\"}");
        JsonNode failed = waitFor(token, id, "FAILED");
        assertThat(failed.path("errorCode").asString()).isEqualTo("RENDER_FAILED");
        assertThat(failed.path("errorMessage").asString()).contains("Chrome crashed");
        assertThat(call("GET", "/api/projects/" + id + "/video", null, token).statusCode()).isEqualTo(404);

        HttpResponse<byte[]> retry = call("POST", "/api/projects/" + id + "/retry", null, token);
        assertThat(retry.statusCode()).isEqualTo(202);
        assertThat(read(retry).path("status").asString()).isEqualTo("RENDERING");
        // the same job is sent to the worker again; no new job is created
        assertThat(takeRequest(id).path("jobId").asString()).isEqualTo(jobId);
        assertThat(read(call("GET", "/api/projects/" + id, null, token)).path("jobs").size()).isEqualTo(3);
    }

    @Test
    void renderNeedsAllFilesReady() throws Exception {
        String token = login();
        String id = read(call("POST", "/api/projects", """
                {"idea": "too early to render", "language": "EN", "durationSeconds": 30}
                """, token)).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");

        HttpResponse<byte[]> early = call("POST", "/api/projects/" + id + "/render", null, token);
        assertThat(early.statusCode()).isEqualTo(409);
        assertThat(call("GET", "/api/projects/" + id + "/video", null, token).statusCode()).isEqualTo(404);
    }

    @Test
    void othersCannotRenderOrDownload() throws Exception {
        String owner = login();
        String id = readyProject(owner, "private render");
        String stranger = login();

        assertThat(call("POST", "/api/projects/" + id + "/render", null, stranger).statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/projects/" + id + "/video", null, stranger).statusCode()).isEqualTo(404);
    }

    @Test
    void badMessagesFromTheWorkerAreIgnored() throws Exception {
        reply("this is not json");
        reply("{\"jobId\":\"" + UUID.randomUUID() + "\",\"type\":\"SUCCEEDED\"}");
        // the listener is still alive: a normal flow still works afterwards
        String token = login();
        String id = readyProject(token, "after bad messages");
        call("POST", "/api/projects/" + id + "/render", null, token);
        String jobId = takeRequest(id).path("jobId").asString();
        reply("{\"jobId\":\"" + jobId + "\",\"type\":\"FAILED\",\"error\":\"x\"}");
        waitFor(token, id, "FAILED");
    }
}
