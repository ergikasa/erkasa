package com.erkasa.backend.features.promptvideo;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.features.promptvideo.project.ProjectEvents;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 4.5: the browser gets a live snapshot for every change, from script to ASSETS_READY. */
class ProjectEventsTests extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ProjectEvents events;

    private String login() throws Exception {
        String email = "sse-" + UUID.randomUUID() + "@example.com";
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

    /** An open SSE connection; every "data:" line lands in the queue as JSON. */
    private final class SseConnection implements AutoCloseable {
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final HttpResponse<Stream<String>> response;
        final Thread reader;

        SseConnection(String token, String projectId) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri("/api/projects/" + projectId + "/events"))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "text/event-stream")
                    .GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            reader = new Thread(() -> {
                try {
                    response.body().forEach(line -> {
                        if (line.startsWith("data:")) {
                            events.add(json.readTree(line.substring(5).trim()));
                        }
                    });
                } catch (RuntimeException closed) {
                    // stream closed at the end of the test
                }
            });
            reader.setDaemon(true);
            reader.start();
        }

        /** Waits for an event with this status, keeping every event seen on the way. */
        JsonNode waitFor(String status, List<JsonNode> seen) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                JsonNode e = events.poll(200, TimeUnit.MILLISECONDS);
                if (e != null) {
                    seen.add(e);
                    if (status.equals(e.path("status").asString())) {
                        return e;
                    }
                }
            }
            throw new AssertionError("No event with status " + status + "; seen: " + seen);
        }

        @Override
        public void close() {
            response.body().close();
        }
    }

    @Test
    void streamFollowsTheProjectFromScriptToAllFilesReady() throws Exception {
        String token = login();
        String id = json.readTree(call("POST", "/api/projects", """
                {"idea": "live updates", "language": "EN", "durationSeconds": 30}
                """, token).body()).path("id").asString();

        try (SseConnection stream = new SseConnection(token, id)) {
            assertThat(stream.response.statusCode()).isEqualTo(200);
            assertThat(stream.response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");

            List<JsonNode> seen = new ArrayList<>();
            // the first event comes at once (SCRIPT_PENDING or already SCRIPT_REVIEW), then the script
            JsonNode review = stream.waitFor("SCRIPT_REVIEW", seen);
            assertThat(review.path("projectId").asString()).isEqualTo(id);
            assertThat(review.path("title").asString()).contains("live updates");

            assertThat(call("POST", "/api/projects/" + id + "/approve", null, token).statusCode()).isEqualTo(200);
            JsonNode ready = stream.waitFor("ASSETS_READY", seen);

            assertThat(ready.path("total").asInt()).isPositive();
            assertThat(ready.path("done").asInt()).isEqualTo(ready.path("total").asInt());
            // there was progress on the way: at least one ASSETS_PENDING event with some files done
            assertThat(seen).anySatisfy(e -> {
                assertThat(e.path("status").asString()).isEqualTo("ASSETS_PENDING");
                assertThat(e.path("done").asInt()).isPositive();
            });
        }
    }

    @Test
    void closedStreamsAreForgotten() throws Exception {
        String token = login();
        String id = json.readTree(call("POST", "/api/projects", """
                {"idea": "close me", "language": "EN", "durationSeconds": 30}
                """, token).body()).path("id").asString();

        try (SseConnection stream = new SseConnection(token, id)) {
            stream.waitFor("SCRIPT_REVIEW", new ArrayList<>());
            assertThat(events.watchers(UUID.fromString(id))).isEqualTo(1);
        }
        // the next change notices the closed connection and drops it
        call("POST", "/api/projects/" + id + "/cancel", null, token);
        long deadline = System.currentTimeMillis() + 5_000;
        while (events.watchers(UUID.fromString(id)) > 0 && System.currentTimeMillis() < deadline) {
            events.changed(UUID.fromString(id));
            Thread.sleep(100);
        }
        assertThat(events.watchers(UUID.fromString(id))).isZero();
    }

    @Test
    void othersAndAnonymousCannotListen() throws Exception {
        String owner = login();
        String id = json.readTree(call("POST", "/api/projects", """
                {"idea": "private stream", "language": "EN", "durationSeconds": 30}
                """, owner).body()).path("id").asString();

        assertThat(call("GET", "/api/projects/" + id + "/events", null, login()).statusCode()).isEqualTo(404);
        assertThat(get("/api/projects/" + id + "/events").statusCode()).isEqualTo(401);
    }
}
