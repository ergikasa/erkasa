package com.erkasa.backend.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Phase 8: Paddle webhooks add bought credits once, take them back after a refund, and anything
 * without a valid signature is refused. (Test packs and secret: AbstractIntegrationTest.)
 */
class PaddleWebhookTests extends AbstractIntegrationTest {

    private static final String SECRET = "pdl_ntfset_test_secret";
    private static final int BONUS = 10_000;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- helpers ----------

    private record Account(String id, String token) {
    }

    private Account register() throws Exception {
        String email = "pay-" + UUID.randomUUID() + "@example.com";
        postJson("/api/auth/register", """
                {"email": "%s", "password": "secret123"}
                """.formatted(email));
        String body = postJson("/api/auth/login", """
                {"email": "%s", "password": "secret123"}
                """.formatted(email)).body();
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        String token = m.group(1);
        return new Account(json.readTree(get(token, "/api/me").body()).path("id").asString(), token);
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private int balance(Account a) throws Exception {
        return json.readTree(get(a.token(), "/api/credits").body()).path("balance").asInt();
    }

    private HttpResponse<String> webhook(String body, String signature) throws Exception {
        HttpRequest.Builder r = HttpRequest.newBuilder(uri("/api/billing/paddle/webhook"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            r.header("Paddle-Signature", signature);
        }
        return http.send(r.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> signed(String body) throws Exception {
        long ts = Instant.now().getEpochSecond();
        return webhook(body, "ts=" + ts + ";h1=" + PaddleSignature.hex(SECRET, ts + ":" + body));
    }

    private static String completed(String txn, String userId, String priceId, int quantity, String total) {
        return """
                {"event_id":"evt_%s","event_type":"transaction.completed","occurred_at":"2026-10-22T10:00:00Z",
                 "data":{"id":"%s","status":"completed","custom_data":{"user_id":"%s"},"currency_code":"USD",
                         "items":[{"price":{"id":"%s"},"quantity":%d}],
                         "details":{"totals":{"total":"%s"}}}}
                """.formatted(UUID.randomUUID(), txn, userId, priceId, quantity, total);
    }

    private static String refund(String txn, String type, String total) {
        return """
                {"event_id":"evt_%s","event_type":"adjustment.updated",
                 "data":{"id":"adj_1","action":"refund","type":"%s","status":"approved","transaction_id":"%s",
                         "totals":{"total":"%s"}}}
                """.formatted(UUID.randomUUID(), type, txn, total);
    }

    private static String txn() {
        return "txn_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26);
    }

    // ---------- tests ----------

    @Test
    void theAppGetsThePacksAndThePaddleSettings() throws Exception {
        Account a = register();
        JsonNode packs = json.readTree(get(a.token(), "/api/billing/packs").body());
        assertThat(packs.path("enabled").asBoolean()).isTrue();
        assertThat(packs.path("environment").asString()).isEqualTo("sandbox");
        assertThat(packs.path("clientToken").asString()).isEqualTo("test_client_token");
        assertThat(packs.path("packs").size()).isEqualTo(3);
        assertThat(packs.path("packs").get(1).path("credits").asInt()).isEqualTo(900);
        assertThat(packs.path("packs").get(1).path("displayPrice").asString()).isEqualTo("$12");
    }

    @Test
    void aCompletedPaymentAddsTheCreditsOnce() throws Exception {
        Account a = register();
        String txn = txn();
        String body = completed(txn, a.id(), "pri_test_creator", 1, "1200");

        assertThat(signed(body).statusCode()).isEqualTo(200);
        assertThat(balance(a)).isEqualTo(BONUS + 900);

        // Paddle sends the same event again: nothing more
        assertThat(signed(body).statusCode()).isEqualTo(200);
        assertThat(balance(a)).isEqualTo(BONUS + 900);

        JsonNode last = json.readTree(get(a.token(), "/api/credits").body()).path("transactions").get(0);
        assertThat(last.path("reason").asString()).isEqualTo("PURCHASE");
        assertThat(last.path("note").asString()).contains("creator").contains(txn);
        assertThat(jdbc.queryForObject("select count(*) from payments where paddle_transaction_id = ?", Integer.class, txn)).isEqualTo(1);
    }

    @Test
    void quantityMultipliesThePack() throws Exception {
        Account a = register();
        signed(completed(txn(), a.id(), "pri_test_starter", 2, "1000"));
        assertThat(balance(a)).isEqualTo(BONUS + 600);
    }

    @Test
    void withoutAValidSignatureNothingHappens() throws Exception {
        Account a = register();
        String body = completed(txn(), a.id(), "pri_test_pro", 1, "2900");

        assertThat(webhook(body, null).statusCode()).isEqualTo(401);
        long ts = Instant.now().getEpochSecond();
        assertThat(webhook(body, "ts=" + ts + ";h1=" + PaddleSignature.hex("wrong", ts + ":" + body)).statusCode()).isEqualTo(401);
        assertThat(balance(a)).isEqualTo(BONUS);
    }

    @Test
    void unknownPricesAndUsersAddNothing() throws Exception {
        Account a = register();
        assertThat(signed(completed(txn(), a.id(), "pri_something_else", 1, "500")).statusCode()).isEqualTo(200);
        assertThat(signed(completed(txn(), UUID.randomUUID().toString(), "pri_test_pro", 1, "2900")).statusCode()).isEqualTo(200);
        assertThat(balance(a)).isEqualTo(BONUS);
    }

    @Test
    void aFullRefundTakesTheCreditsBackOnce() throws Exception {
        Account a = register();
        String txn = txn();
        signed(completed(txn, a.id(), "pri_test_pro", 1, "2900"));
        assertThat(balance(a)).isEqualTo(BONUS + 2700);

        String body = refund(txn, "full", "2900");
        signed(body);
        signed(body);
        assertThat(balance(a)).isEqualTo(BONUS);
        assertThat(jdbc.queryForObject("select status from payments where paddle_transaction_id = ?", String.class, txn)).isEqualTo("REFUNDED");
    }

    @Test
    void aPartialRefundTakesTheSameShare() throws Exception {
        Account a = register();
        String txn = txn();
        signed(completed(txn, a.id(), "pri_test_starter", 1, "500"));
        signed(refund(txn, "partial", "250")); // half the money back -> half the credits
        assertThat(balance(a)).isEqualTo(BONUS + 150);
    }

    @Test
    void aRefundNeverTakesMoreThanTheBalance() throws Exception {
        Account a = register();
        String txn = txn();
        signed(completed(txn, a.id(), "pri_test_creator", 1, "1200"));
        jdbc.update("update users set credits = 100 where id = ?::uuid", a.id()); // most of it was spent
        signed(refund(txn, "full", "1200"));
        assertThat(balance(a)).isZero();
    }
}
