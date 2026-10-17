package com.erkasa.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.common.error.ApiException;

/**
 * Sub-task 2.5: POST /api/auth/google
 *
 * Tests cannot get real tokens from Google, so the verifier is replaced by a Mockito mock:
 * we decide what "Google" says for each fake token. Everything after verification is real.
 */
class GoogleLoginTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "secret123";

    @MockitoBean
    private GoogleTokenVerifier googleVerifier;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- helpers ----------

    private static String uniqueEmail() {
        return "google-" + UUID.randomUUID() + "@gmail.com";
    }

    /** Makes the fake Google accept this token as the given person. Returns the token. */
    private String googleSays(String sub, String email, boolean emailVerified, String name) {
        String token = "fake-google-token-" + UUID.randomUUID();
        when(googleVerifier.verify(token)).thenReturn(new GoogleIdentity(sub, email, emailVerified, name));
        return token;
    }

    private HttpResponse<String> googleLogin(String idToken) throws Exception {
        return postJson("/api/auth/google", "{\"idToken\": \"" + idToken + "\"}");
    }

    private HttpResponse<String> passwordLogin(String email) throws Exception {
        return postJson("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, PASSWORD));
    }

    private Map<String, Object> userRow(String email) {
        return jdbc.queryForMap(
                "select id, google_sub, password_hash, email_verified from users where email = ?", email);
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("field %s in %s", name, json).isTrue();
        return m.group(1);
    }

    // ---------- tests ----------

    @Test
    void firstGoogleSignInCreatesAnAccount() throws Exception {
        String email = uniqueEmail();
        String sub = "sub-" + UUID.randomUUID();

        HttpResponse<String> response = googleLogin(googleSays(sub, email, true, "Ergi Kasa"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"tokenType\":\"Bearer\"")
                .contains("\"email\":\"" + email + "\"")
                .contains("\"displayName\":\"Ergi Kasa\"")
                .contains("\"emailVerified\":true");

        Map<String, Object> row = userRow(email);
        assertThat(row.get("google_sub")).isEqualTo(sub);
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("email_verified")).isEqualTo(true);
    }

    @Test
    void secondGoogleSignInUsesTheSameAccount() throws Exception {
        String email = uniqueEmail();
        String sub = "sub-" + UUID.randomUUID();

        googleLogin(googleSays(sub, email, true, "Ergi"));
        googleLogin(googleSays(sub, email, true, "Ergi"));

        Integer count = jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void googleAccountIsFoundBySubEvenIfTheEmailChanged() throws Exception {
        String oldEmail = uniqueEmail();
        String sub = "sub-" + UUID.randomUUID();
        googleLogin(googleSays(sub, oldEmail, true, "Ergi"));

        HttpResponse<String> response = googleLogin(googleSays(sub, uniqueEmail(), true, "Ergi"));

        // Same account (matched on sub), still shown with the original email.
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"email\":\"" + oldEmail + "\"");
    }

    @Test
    void existingUnverifiedPasswordAccountIsLinkedAndItsPasswordRemoved() throws Exception {
        String email = uniqueEmail();
        postJson("/api/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "Someone"}
                """.formatted(email, PASSWORD));
        Object idBefore = userRow(email).get("id");

        HttpResponse<String> response = googleLogin(googleSays("sub-" + UUID.randomUUID(), email, true, "Ergi"));

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> row = userRow(email);
        assertThat(row.get("id")).isEqualTo(idBefore);
        assertThat(row.get("google_sub")).isNotNull();
        assertThat(row.get("email_verified")).isEqualTo(true);
        // The password was never proven to belong to the email owner, so it no longer works.
        assertThat(row.get("password_hash")).isNull();
        assertThat(passwordLogin(email).statusCode()).isEqualTo(401);
    }

    @Test
    void emailAlreadyLinkedToAnotherGoogleAccountReturns409() throws Exception {
        String email = uniqueEmail();
        googleLogin(googleSays("sub-A-" + UUID.randomUUID(), email, true, "Ergi"));

        HttpResponse<String> response = googleLogin(googleSays("sub-B-" + UUID.randomUUID(), email, true, "Other"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("\"code\":\"GOOGLE_ACCOUNT_CONFLICT\"");
    }

    @Test
    void unverifiedGoogleEmailIsRejected() throws Exception {
        String email = uniqueEmail();

        HttpResponse<String> response = googleLogin(googleSays("sub-" + UUID.randomUUID(), email, false, "Ergi"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"GOOGLE_EMAIL_NOT_VERIFIED\"");
        Integer count = jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
        assertThat(count).isZero();
    }

    @Test
    void invalidGoogleTokenReturns401() throws Exception {
        when(googleVerifier.verify("forged-token")).thenThrow(new ApiException(
                HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN", "Google sign-in failed. Please try again"));

        HttpResponse<String> response = googleLogin("forged-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_GOOGLE_TOKEN\"");
    }

    @Test
    void googleOnlyUserCannotLogInWithAPassword() throws Exception {
        String email = uniqueEmail();
        googleLogin(googleSays("sub-" + UUID.randomUUID(), email, true, "Ergi"));

        HttpResponse<String> response = passwordLogin(email);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    @Test
    void returnedAccessTokenWorksOnProtectedEndpoints() throws Exception {
        String email = uniqueEmail();
        String body = googleLogin(googleSays("sub-" + UUID.randomUUID(), email, true, "Ergi")).body();

        HttpRequest me = HttpRequest.newBuilder(uri("/api/me"))
                .header("Authorization", "Bearer " + field(body, "accessToken"))
                .GET()
                .build();
        HttpResponse<String> response = http.send(me, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"email\":\"" + email + "\"");
    }

    @Test
    void missingIdTokenReturns400() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/google", "{}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"idToken\"");
    }
}