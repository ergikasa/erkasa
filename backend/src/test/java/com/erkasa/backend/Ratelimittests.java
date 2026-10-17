package com.erkasa.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.erkasa.backend.common.web.RateLimiter;

/**
 * Sub-task 2.7: rate limiting, switched on with a tiny limit (3 logins per 10 minutes)
 * so the test can reach it quickly.
 *
 * Rule 0 in application.yml is "login"; we override its limit and window here.
 * The other rules from application.yml stay as they are.
 */
class RateLimitTests extends AbstractIntegrationTest {

    private static final int LOGIN_LIMIT = 3;

    /** Runs after the parent's @DynamicPropertySource, so these values win. */
    @DynamicPropertySource
    static void rateLimitOn(DynamicPropertyRegistry registry) {
        registry.add("erkasa.rate-limit.enabled", () -> "true");
        registry.add("erkasa.rate-limit.trust-proxy", () -> "true");
        registry.add("erkasa.rate-limit.rules[0].name", () -> "login");
        registry.add("erkasa.rate-limit.rules[0].method", () -> "POST");
        registry.add("erkasa.rate-limit.rules[0].path", () -> "/api/auth/login");
        registry.add("erkasa.rate-limit.rules[0].limit", () -> String.valueOf(LOGIN_LIMIT));
        registry.add("erkasa.rate-limit.rules[0].window", () -> "10m");
    }

    @Autowired
    private RateLimiter limiter;

    @BeforeEach
    void resetCounters() {
        limiter.clear();
    }

    private HttpResponse<String> login(String fromIp) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\": \"nobody@example.com\", \"password\": \"wrong-pass\"}"));
        if (fromIp != null) {
            request.header("X-Forwarded-For", fromIp);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void requestsUpToTheLimitPassThenAre429() throws Exception {
        for (int i = 0; i < LOGIN_LIMIT; i++) {
            // Reaches the controller: wrong credentials, not rate limited.
            assertThat(login("10.0.0.1").statusCode()).isEqualTo(401);
        }

        HttpResponse<String> blocked = login("10.0.0.1");

        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).contains("\"code\":\"RATE_LIMITED\"", "\"requestId\"", "\"status\":429");
        long retryAfter = Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow());
        assertThat(retryAfter).isBetween(1L, 600L);
    }

    @Test
    void eachIpHasItsOwnLimit() throws Exception {
        for (int i = 0; i < LOGIN_LIMIT; i++) {
            login("10.0.0.2");
        }
        assertThat(login("10.0.0.2").statusCode()).isEqualTo(429);

        // Another caller is not affected.
        assertThat(login("10.0.0.3").statusCode()).isEqualTo(401);
    }

    @Test
    void endpointsWithoutARuleAreNeverLimited() throws Exception {
        for (int i = 0; i < 20; i++) {
            assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        }
    }
}