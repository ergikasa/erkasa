package com.erkasa.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;

/** Sub-task 2.4: POST /api/auth/refresh, /logout, /logout-all */
class RefreshAndLogoutTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "secret123";

    @Autowired
    private JdbcTemplate jdbc;

    /** Access + refresh token pair returned by login or refresh. */
    private record Tokens(String accessToken, String refreshToken) {
    }

    // ---------- helpers ----------

    private String registerUser() throws Exception {
        String email = "refresh-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> response = postJson("/api/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "Ergi"}
                """.formatted(email, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return email;
    }

    private Tokens login(String email) throws Exception {
        HttpResponse<String> response = postJson("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(200);
        return tokensFrom(response.body());
    }

    private HttpResponse<String> refresh(String refreshToken) throws Exception {
        return postJson("/api/auth/refresh", "{\"refreshToken\": \"" + refreshToken + "\"}");
    }

    private HttpResponse<String> logout(String refreshToken) throws Exception {
        return postJson("/api/auth/logout", "{\"refreshToken\": \"" + refreshToken + "\"}");
    }

    private HttpResponse<String> logoutAll(String accessToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/api/auth/logout-all"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> me(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/me"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static Tokens tokensFrom(String json) {
        return new Tokens(field(json, "accessToken"), field(json, "refreshToken"));
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("field %s in %s", name, json).isTrue();
        return m.group(1);
    }

    private static void assertInvalidRefresh(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_REFRESH_TOKEN\"");
    }

    // ---------- refresh ----------

    @Test
    void refreshReturnsANewWorkingPairOfTokens() throws Exception {
        Tokens first = login(registerUser());

        HttpResponse<String> response = refresh(first.refreshToken());

        assertThat(response.statusCode()).isEqualTo(200);
        Tokens second = tokensFrom(response.body());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(me(second.accessToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void aRefreshTokenWorksOnlyOnce() throws Exception {
        Tokens first = login(registerUser());
        assertThat(refresh(first.refreshToken()).statusCode()).isEqualTo(200);

        assertInvalidRefresh(refresh(first.refreshToken()));
    }

    @Test
    void reusingAnOldTokenEndsTheWholeSession() throws Exception {
        Tokens first = login(registerUser());
        Tokens second = tokensFrom(refresh(first.refreshToken()).body());

        // Someone replays the old token (e.g. a thief who copied it).
        assertInvalidRefresh(refresh(first.refreshToken()));

        // The newer token of the same session is now dead too, so the thief cannot use it either.
        assertInvalidRefresh(refresh(second.refreshToken()));
    }

    @Test
    void unknownRefreshTokenReturns401() throws Exception {
        assertInvalidRefresh(refresh("this-token-does-not-exist"));
    }

    @Test
    void expiredRefreshTokenReturns401() throws Exception {
        Tokens tokens = login(registerUser());
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 minute' where token_hash = ?",
                RefreshTokenService.hash(tokens.refreshToken()));

        assertInvalidRefresh(refresh(tokens.refreshToken()));
    }

    @Test
    void refreshWithoutBodyFieldReturns400() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/refresh", "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"refreshToken\"");
    }

    // ---------- logout ----------

    @Test
    void logoutEndsThisSession() throws Exception {
        Tokens tokens = login(registerUser());

        assertThat(logout(tokens.refreshToken()).statusCode()).isEqualTo(204);

        assertInvalidRefresh(refresh(tokens.refreshToken()));
    }

    @Test
    void logoutOnOneDeviceKeepsTheOtherDeviceLoggedIn() throws Exception {
        String email = registerUser();
        Tokens phone = login(email);
        Tokens laptop = login(email);

        logout(phone.refreshToken());

        assertInvalidRefresh(refresh(phone.refreshToken()));
        assertThat(refresh(laptop.refreshToken()).statusCode()).isEqualTo(200);
    }

    @Test
    void logoutWithUnknownTokenStillReturns204() throws Exception {
        assertThat(logout("this-token-does-not-exist").statusCode()).isEqualTo(204);
    }

    // ---------- logout-all ----------

    @Test
    void logoutAllEndsEverySession() throws Exception {
        String email = registerUser();
        Tokens phone = login(email);
        Tokens laptop = login(email);

        assertThat(logoutAll(laptop.accessToken()).statusCode()).isEqualTo(204);

        assertInvalidRefresh(refresh(phone.refreshToken()));
        assertInvalidRefresh(refresh(laptop.refreshToken()));
    }

    @Test
    void logoutAllNeedsAnAccessToken() throws Exception {
        HttpResponse<String> response = logoutAll(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHORIZED\"");
    }
}