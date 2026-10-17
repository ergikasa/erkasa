package com.erkasa.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.AbstractIntegrationTest;

/** Sub-task 2.3: which endpoints need a token, and what happens without one. */
class SecurityTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "secret123";

    /** Registers + logs in a fresh user. Returns {email, accessToken}. */
    private String[] newLoggedInUser() throws Exception {
        String email = "sec-" + UUID.randomUUID() + "@example.com";
        postJson("/api/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "Ergi"}
                """.formatted(email, PASSWORD));
        HttpResponse<String> login = postJson("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);

        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(login.body());
        assertThat(m.find()).isTrue();
        return new String[] {email, m.group(1)};
    }

    private HttpResponse<String> getWithToken(String path, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ---------- protected endpoint ----------

    @Test
    void meWithoutTokenReturns401InOurFormat() throws Exception {
        HttpResponse<String> response = get("/api/me");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHORIZED\"", "\"requestId\"", "\"instance\":\"/api/me\"");
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
    }

    @Test
    void meWithGarbageTokenReturns401() throws Exception {
        HttpResponse<String> response = getWithToken("/api/me", "not-a-real-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void meWithTamperedTokenReturns401() throws Exception {
        String token = newLoggedInUser()[1];
        int i = token.length() - 10;
        String tampered = token.substring(0, i) + (token.charAt(i) == 'A' ? 'B' : 'A') + token.substring(i + 1);

        assertThat(getWithToken("/api/me", tampered).statusCode()).isEqualTo(401);
    }

    @Test
    void meWithValidTokenReturnsTheLoggedInUser() throws Exception {
        String[] user = newLoggedInUser();

        HttpResponse<String> response = getWithToken("/api/me", user[1]);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"email\":\"" + user[0] + "\"")
                .contains("\"role\":\"USER\"")
                .doesNotContain("password");
    }

    @Test
    void unknownUrlWithValidTokenReturns404() throws Exception {
        String token = newLoggedInUser()[1];

        HttpResponse<String> response = getWithToken("/api/does-not-exist", token);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":\"NOT_FOUND\"", "\"requestId\"");
    }

    // ---------- public endpoints stay open ----------

    @Test
    void loginIsReachableWithoutToken() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/login", """
                {"email": "nobody@example.com", "password": "whatever1"}
                """);

        // INVALID_CREDENTIALS (from our controller), not UNAUTHORIZED (from the security filter).
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    @Test
    void healthIsReachableWithoutToken() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    // ---------- CORS ----------

    @Test
    void browserPreflightFromFrontendIsAllowed() throws Exception {
        HttpRequest preflight = HttpRequest.newBuilder(uri("/api/auth/login"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .build();

        HttpResponse<String> response = http.send(preflight, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("http://localhost:5173");
    }

    @Test
    void browserPreflightFromUnknownSiteIsRejected() throws Exception {
        HttpRequest preflight = HttpRequest.newBuilder(uri("/api/auth/login"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "POST")
                .build();

        HttpResponse<String> response = http.send(preflight, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}