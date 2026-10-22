package com.erkasa.backend.credits;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.features.promptvideo.render.RenderQueues;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 7: 1 credit = 1 second of video. Approving pays, a failed or cancelled video gives the
 * credits back, "try again" pays again, nobody can spend more than they have.
 * (Tests run with a 10000-credit welcome bonus, see AbstractIntegrationTest.)
 */
class CreditTests extends AbstractIntegrationTest {

    private static final int BONUS = 10_000;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private ObjectMapper json;

    // ---------- helpers ----------

    private record Account(String email, String token) {
    }

    private Account register() throws Exception {
        String email = "credits-" + UUID.randomUUID() + "@example.com";
        postJson("/api/auth/register", """
                {"email": "%s", "password": "secret123", "displayName": "Ergi"}
                """.formatted(email));
        return new Account(email, login(email));
    }

    private String login(String email) throws Exception {
        String body = postJson("/api/auth/login", """
                {"email": "%s", "password": "secret123"}
                """.formatted(email)).body();
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).as(body).isTrue();
        return m.group(1);
    }

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode read(HttpResponse<String> res) {
        return json.readTree(res.body());
    }

    private JsonNode credits(String token) throws Exception {
        return read(call("GET", "/api/credits", null, token));
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

    /** A project whose script is ready to approve. */
    private String reviewProject(String token, int seconds) throws Exception {
        String id = read(call("POST", "/api/projects", """
                {"idea": "credits %s", "language": "EN", "durationSeconds": %d}
                """.formatted(UUID.randomUUID(), seconds), token)).path("id").asString();
        waitFor(token, id, "SCRIPT_REVIEW");
        return id;
    }

    private String takeRenderJob(String projectId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Message m = rabbit.receive(RenderQueues.REQUESTS, 1000);
            if (m == null) {
                continue;
            }
            JsonNode req = json.readTree(new String(m.getBody(), StandardCharsets.UTF_8));
            if (projectId.equals(req.path("projectId").asString())) {
                return req.path("jobId").asString();
            }
        }
        throw new AssertionError("No render request for project " + projectId);
    }

    private void setCredits(String email, int credits) {
        jdbc.update("update users set credits = ? where email = ?", credits, email);
    }

    // ---------- tests ----------

    @Test
    void aNewAccountGetsTheWelcomeCredits() throws Exception {
        Account a = register();
        JsonNode c = credits(a.token());
        assertThat(c.path("balance").asInt()).isEqualTo(BONUS);
        assertThat(c.path("transactions").size()).isEqualTo(1);
        assertThat(c.path("transactions").get(0).path("reason").asString()).isEqualTo("SIGNUP_BONUS");
        assertThat(c.path("transactions").get(0).path("amount").asInt()).isEqualTo(BONUS);
        // the app shows the balance from /api/me
        assertThat(read(call("GET", "/api/me", null, a.token())).path("credits").asInt()).isEqualTo(BONUS);
    }

    @Test
    void approvingPaysOneCreditPerSecond() throws Exception {
        Account a = register();
        String id = reviewProject(a.token(), 45);

        assertThat(call("POST", "/api/projects/" + id + "/approve", null, a.token()).statusCode()).isEqualTo(200);

        JsonNode c = credits(a.token());
        assertThat(c.path("balance").asInt()).isEqualTo(BONUS - 45);
        JsonNode charge = c.path("transactions").get(0);
        assertThat(charge.path("reason").asString()).isEqualTo("VIDEO_CHARGE");
        assertThat(charge.path("amount").asInt()).isEqualTo(-45);
        assertThat(charge.path("balanceAfter").asInt()).isEqualTo(BONUS - 45);
        assertThat(charge.path("projectId").asString()).isEqualTo(id);
    }

    @Test
    void notEnoughCreditsKeepsTheScriptAndTheBalance() throws Exception {
        Account a = register();
        String id = reviewProject(a.token(), 30);
        setCredits(a.email(), 10);

        HttpResponse<String> res = call("POST", "/api/projects/" + id + "/approve", null, a.token());
        assertThat(res.statusCode()).isEqualTo(402);
        assertThat(read(res).path("code").asString()).isEqualTo("INSUFFICIENT_CREDITS");

        // nothing happened: still in review, nothing taken, no files started
        JsonNode p = read(call("GET", "/api/projects/" + id, null, a.token()));
        assertThat(p.path("status").asString()).isEqualTo("SCRIPT_REVIEW");
        assertThat(p.path("jobs").size()).isEqualTo(1);
        assertThat(credits(a.token()).path("balance").asInt()).isEqualTo(10);
    }

    @Test
    void cancellingGivesTheCreditsBack() throws Exception {
        Account a = register();
        String id = reviewProject(a.token(), 30);
        call("POST", "/api/projects/" + id + "/approve", null, a.token());
        waitFor(a.token(), id, "ASSETS_READY");

        assertThat(call("POST", "/api/projects/" + id + "/cancel", null, a.token()).statusCode()).isEqualTo(200);

        JsonNode c = credits(a.token());
        assertThat(c.path("balance").asInt()).isEqualTo(BONUS);
        assertThat(c.path("transactions").get(0).path("reason").asString()).isEqualTo("VIDEO_REFUND");
        assertThat(c.path("transactions").get(0).path("amount").asInt()).isEqualTo(30);
    }

    @Test
    void aFailedRenderRefundsAndTryAgainPaysAgain() throws Exception {
        Account a = register();
        String id = reviewProject(a.token(), 30);
        call("POST", "/api/projects/" + id + "/approve", null, a.token());
        waitFor(a.token(), id, "ASSETS_READY");
        call("POST", "/api/projects/" + id + "/render", null, a.token());
        String jobId = takeRenderJob(id);

        rabbit.send("", RenderQueues.RESULTS, MessageBuilder
                .withBody(("{\"jobId\":\"" + jobId + "\",\"type\":\"FAILED\",\"error\":\"Chrome crashed\"}").getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build());
        waitFor(a.token(), id, "FAILED");
        assertThat(credits(a.token()).path("balance").asInt()).isEqualTo(BONUS);

        assertThat(call("POST", "/api/projects/" + id + "/retry", null, a.token()).statusCode()).isEqualTo(202);
        assertThat(credits(a.token()).path("balance").asInt()).isEqualTo(BONUS - 30);
        takeRenderJob(id); // leave the queue clean for other tests
    }

    @Test
    void tryAgainWithoutCreditsIsRefused() throws Exception {
        Account a = register();
        String id = reviewProject(a.token(), 30);
        call("POST", "/api/projects/" + id + "/approve", null, a.token());
        waitFor(a.token(), id, "ASSETS_READY");
        call("POST", "/api/projects/" + id + "/render", null, a.token());
        String jobId = takeRenderJob(id);
        rabbit.send("", RenderQueues.RESULTS, MessageBuilder
                .withBody(("{\"jobId\":\"" + jobId + "\",\"type\":\"FAILED\",\"error\":\"x\"}").getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build());
        waitFor(a.token(), id, "FAILED");
        setCredits(a.email(), 5);

        HttpResponse<String> res = call("POST", "/api/projects/" + id + "/retry", null, a.token());
        assertThat(res.statusCode()).isEqualTo(402);
        assertThat(read(call("GET", "/api/projects/" + id, null, a.token())).path("status").asString()).isEqualTo("FAILED");
    }

    @Test
    void onlyAdminsCanGiveCredits() throws Exception {
        Account user = register();
        Account admin = register();
        String body = """
                {"email": "%s", "amount": 50, "note": "beta tester"}
                """.formatted(user.email());

        assertThat(call("POST", "/api/admin/credits", body, admin.token()).statusCode()).isEqualTo(403);

        jdbc.update("update users set role = 'ADMIN' where email = ?", admin.email());
        String adminToken = login(admin.email()); // the role is in the token
        HttpResponse<String> res = call("POST", "/api/admin/credits", body, adminToken);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(read(res).path("balance").asInt()).isEqualTo(BONUS + 50);

        JsonNode grant = credits(user.token()).path("transactions").get(0);
        assertThat(grant.path("reason").asString()).isEqualTo("ADMIN_GRANT");
        assertThat(grant.path("note").asString()).contains("beta tester").contains(admin.email());
    }
}
