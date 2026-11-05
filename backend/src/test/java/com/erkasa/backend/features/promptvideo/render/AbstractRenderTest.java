package com.erkasa.backend.features.promptvideo.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Helpers for the render tests of V2 (A3 lease/fencing, A4 watchdog): a project that is RENDERING,
 * its render request, and worker messages.
 *
 * The tests play the worker but call RenderJobService.onResult() directly instead of going through
 * RabbitMQ: several test contexts listen on render.results and could handle messages out of order,
 * and "a message was ignored" can only be checked when we know it has been handled.
 */
abstract class AbstractRenderTest extends AbstractIntegrationTest {

    @Autowired
    protected RenderJobService renders;

    @Autowired
    protected RabbitTemplate rabbit;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ProjectRepository projects;

    @Autowired
    protected JobRepository jobs;

    // ---------- helpers ----------

    protected String login() throws Exception {
        String email = "lease-" + UUID.randomUUID() + "@example.com";
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

    protected JsonNode call(String method, String path, String token) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return json.readTree(http.send(r, HttpResponse.BodyHandlers.ofString()).body());
    }

    protected JsonNode post(String path, String body, String token) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return json.readTree(http.send(r, HttpResponse.BodyHandlers.ofString()).body());
    }

    protected void waitFor(String token, String id, String status) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = call("GET", "/api/projects/" + id, token).path("status").asString();
            if (status.equals(last)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Project " + id + " never reached " + status + ", last: " + last);
    }

    /** The owner's token of the project made by the last rendering() call. */
    protected String token;

    /** A project that is RENDERING, and the render request the worker would get. */
    protected JsonNode rendering() throws Exception {
        String token = login();
        this.token = token;
        String id = post("/api/projects", """
                {"idea": "lease %s", "language": "EN", "durationSeconds": 30}
                """.formatted(UUID.randomUUID()), token).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");
        post("/api/projects/" + id + "/approve", "", token);
        waitFor(token, id, "ASSETS_READY");
        post("/api/projects/" + id + "/render", "", token);
        return takeRequest(id);
    }

    /** Takes this project's next render request from the queue, as the worker would. */
    protected JsonNode takeRequest(String projectId) {
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

    protected static UUID jobId(JsonNode req) {
        return UUID.fromString(req.path("jobId").asString());
    }

    protected static UUID dispatchId(JsonNode req) {
        return UUID.fromString(req.path("dispatchId").asString());
    }

    protected void send(JsonNode req, UUID dispatch, String worker, String type, String error) {
        send(req, dispatch, worker, type, error, null);
    }

    protected void send(JsonNode req, UUID dispatch, String worker, String type, String error, String errorCode) {
        renders.onResult(new RenderMessages.Result(jobId(req), type, dispatch, worker, 50,
                RenderMessages.Result.SUCCEEDED.equals(type) ? 30.0 : null,
                RenderMessages.Result.SUCCEEDED.equals(type) ? 1234L : null,
                RenderMessages.Result.SUCCEEDED.equals(type) ? 60.0 : null, error, errorCode));
    }

    protected void send(JsonNode req, String worker, String type) {
        send(req, dispatchId(req), worker, type, "failed on " + worker);
    }

    protected ProjectStatus projectStatus(JsonNode req) {
        return projects.findById(UUID.fromString(req.path("projectId").asString())).orElseThrow().getStatus();
    }

    protected static String projectId(JsonNode req) {
        return req.path("projectId").asString();
    }

    protected Job job(JsonNode req) {
        return jobs.findById(jobId(req)).orElseThrow();
    }

    protected int refunds(JsonNode req) {
        return credits(req, "VIDEO_REFUND");
    }

    protected int credits(JsonNode req, String reason) {
        return jdbc.queryForObject("select count(*) from credit_transactions where project_id = ?::uuid and reason = ?",
                Integer.class, projectId(req), reason);
    }

    protected int outboxRequests(JsonNode req) {
        return jdbc.queryForObject("select count(*) from outbox_messages where queue = 'render.requests' and payload ->> 'jobId' = ?",
                Integer.class, req.path("jobId").asString());
    }
}
