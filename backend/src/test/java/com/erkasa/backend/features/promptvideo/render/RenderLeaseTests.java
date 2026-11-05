package com.erkasa.backend.features.promptvideo.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.jobs.Job;
import com.erkasa.backend.jobs.JobKind;
import com.erkasa.backend.jobs.JobRepository;
import com.erkasa.backend.jobs.JobStatus;
import com.erkasa.backend.jobs.ProjectRepository;
import com.erkasa.backend.jobs.ProjectStatus;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * V2 task A3: lease, ownership and fencing of renders.
 *
 * The test plays the worker but calls RenderJobService.onResult() directly instead of going through
 * RabbitMQ: several test contexts listen on render.results and could handle messages out of order,
 * and "a message was ignored" can only be checked when we know it has been handled.
 */
class RenderLeaseTests extends AbstractIntegrationTest {

    @Autowired
    private RenderJobService renders;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private JobRepository jobs;

    // ---------- helpers ----------

    private String login() throws Exception {
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

    private JsonNode call(String method, String path, String token) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return json.readTree(http.send(r, HttpResponse.BodyHandlers.ofString()).body());
    }

    private JsonNode post(String path, String body, String token) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return json.readTree(http.send(r, HttpResponse.BodyHandlers.ofString()).body());
    }

    private void waitFor(String token, String id, String status) throws Exception {
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

    /** A project that is RENDERING, and the render request the worker would get. */
    private JsonNode rendering() throws Exception {
        String token = login();
        String id = post("/api/projects", """
                {"idea": "lease %s", "language": "EN", "durationSeconds": 30}
                """.formatted(UUID.randomUUID()), token).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");
        post("/api/projects/" + id + "/approve", "", token);
        waitFor(token, id, "ASSETS_READY");
        post("/api/projects/" + id + "/render", "", token);

        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Message m = rabbit.receive(RenderQueues.REQUESTS, 1000);
            if (m == null) {
                continue;
            }
            JsonNode req = json.readTree(new String(m.getBody(), StandardCharsets.UTF_8));
            if (id.equals(req.path("projectId").asString())) {
                return req;
            }
        }
        throw new AssertionError("No render request for project " + id);
    }

    private static UUID jobId(JsonNode req) {
        return UUID.fromString(req.path("jobId").asString());
    }

    private static UUID dispatchId(JsonNode req) {
        return UUID.fromString(req.path("dispatchId").asString());
    }

    private void send(JsonNode req, UUID dispatch, String worker, String type, String error) {
        renders.onResult(new RenderMessages.Result(jobId(req), type, dispatch, worker, 50,
                RenderMessages.Result.SUCCEEDED.equals(type) ? 30.0 : null,
                RenderMessages.Result.SUCCEEDED.equals(type) ? 1234L : null,
                RenderMessages.Result.SUCCEEDED.equals(type) ? 60.0 : null, error));
    }

    private void send(JsonNode req, String worker, String type) {
        send(req, dispatchId(req), worker, type, "failed on " + worker);
    }

    private ProjectStatus projectStatus(JsonNode req) {
        return projects.findById(UUID.fromString(req.path("projectId").asString())).orElseThrow().getStatus();
    }

    private Job job(JsonNode req) {
        return jobs.findById(jobId(req)).orElseThrow();
    }

    private int refunds(JsonNode req) {
        return jdbc.queryForObject("select count(*) from credit_transactions where project_id = ?::uuid and reason = 'VIDEO_REFUND'",
                Integer.class, req.path("projectId").asString());
    }

    // ---------- tests ----------

    @Test
    void aClaimTakesTheLeaseAndHeartbeatsKeepIt() throws Exception {
        JsonNode req = rendering();
        Instant before = Instant.now();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);

        Job claimed = job(req);
        assertThat(claimed.getLeaseOwner()).isEqualTo("worker-a");
        assertThat(claimed.getClaimedAt()).isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(claimed.getLeaseUntil()).isAfter(claimed.getClaimedAt().plus(Duration.ofSeconds(110)));
        // 30 s video: max(10 min, 6 s x 30) = 10 min
        assertThat(Duration.between(claimed.getClaimedAt(), claimed.getDeadlineAt())).isEqualTo(Duration.ofMinutes(10));

        Thread.sleep(50);
        send(req, "worker-a", RenderMessages.Result.PROGRESS);
        Job beaten = job(req);
        assertThat(beaten.getLeaseUntil()).isAfter(claimed.getLeaseUntil());
        assertThat(beaten.getClaimedAt()).isEqualTo(claimed.getClaimedAt());
        assertThat(beaten.getDeadlineAt()).isEqualTo(claimed.getDeadlineAt());
    }

    @Test
    void aResultOfAnOldOrMissingDispatchIsIgnored() throws Exception {
        JsonNode req = rendering();

        send(req, UUID.randomUUID(), "worker-a", RenderMessages.Result.SUCCEEDED, null);
        send(req, null, "worker-a", RenderMessages.Result.SUCCEEDED, null);
        send(req, UUID.randomUUID(), "worker-a", RenderMessages.Result.FAILED, "stale failure");
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.RENDERING);
        assertThat(job(req).getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(refunds(req)).isZero();

        // the current dispatch still finishes it
        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.DONE);
        assertThat(jdbc.queryForObject("select output_key from job_steps where job_id = ? and type = 'RENDER'",
                String.class, jobId(req))).isEqualTo(req.path("outputKey").asString());
    }

    @Test
    void aSecondWorkerCannotClaimOrFinishWhileTheFirstHoldsTheLease() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);

        // RabbitMQ gave the same request to worker-b too
        send(req, "worker-b", RenderMessages.Result.CLAIMED);
        assertThat(job(req).getLeaseOwner()).isEqualTo("worker-a");
        send(req, "worker-b", RenderMessages.Result.PROGRESS);
        send(req, "worker-b", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.RENDERING);

        // only the owner's result counts
        send(req, "worker-a", RenderMessages.Result.FAILED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.FAILED);
        assertThat(projects.findById(UUID.fromString(req.path("projectId").asString())).orElseThrow()
                .getErrorMessage()).isEqualTo("failed on worker-a");
        assertThat(refunds(req)).isEqualTo(1);
    }

    @Test
    void aSecondFinalResultChangesNothing() throws Exception {
        JsonNode req = rendering();
        send(req, "worker-a", RenderMessages.Result.CLAIMED);
        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.DONE);

        send(req, "worker-a", RenderMessages.Result.SUCCEEDED);
        send(req, "worker-a", RenderMessages.Result.FAILED);
        send(req, "worker-a", RenderMessages.Result.PROGRESS);
        assertThat(projectStatus(req)).isEqualTo(ProjectStatus.DONE);
        assertThat(job(req).getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(refunds(req)).isZero();
    }

    @Test
    void theDatabaseRefusesASecondActiveRenderForTheSameProject() throws Exception {
        JsonNode req = rendering();
        UUID projectId = UUID.fromString(req.path("projectId").asString());

        assertThatThrownBy(() -> jobs.saveAndFlush(Job.queue(projectId, JobKind.RENDER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jobs.countByProjectIdAndKind(projectId, JobKind.RENDER)).isEqualTo(1);
    }
}
