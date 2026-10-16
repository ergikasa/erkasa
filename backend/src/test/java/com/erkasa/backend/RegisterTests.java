package com.erkasa.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;

/** Sub-task 2.1: POST /api/auth/register */
class RegisterTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    /** Every test uses a fresh email, so tests never clash with each other. */
    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private static String body(String email, String password, String displayName) {
        return """
                {"email": "%s", "password": "%s", "displayName": "%s"}
                """.formatted(email, password, displayName);
    }

    @Test
    void registersNewUser() throws Exception {
        String email = uniqueEmail();

        HttpResponse<String> response = postJson("/api/auth/register", body(email, "secret123", "Ergi"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body())
                .contains("\"email\":\"" + email + "\"")
                .contains("\"displayName\":\"Ergi\"")
                .contains("\"emailVerified\":false")
                .contains("\"role\":\"USER\"")
                .doesNotContain("password")
                .doesNotContain("secret123");
    }

    @Test
    void storesOnlyABcryptHashNeverThePassword() throws Exception {
        String email = uniqueEmail();

        postJson("/api/auth/register", body(email, "secret123", "Ergi"));

        String hash = jdbc.queryForObject(
                "select password_hash from users where email = ?", String.class, email);
        assertThat(hash).startsWith("$2").isNotEqualTo("secret123");
    }

    @Test
    void emailIsLowercased() throws Exception {
        String email = uniqueEmail();

        HttpResponse<String> response = postJson("/api/auth/register",
                body(email.toUpperCase(), "secret123", "Ergi"));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"email\":\"" + email + "\"");
    }

    @Test
    void sameEmailTwiceReturns409() throws Exception {
        String email = uniqueEmail();
        postJson("/api/auth/register", body(email, "secret123", "Ergi"));

        HttpResponse<String> second = postJson("/api/auth/register",
                body(email.toUpperCase(), "another123", "Someone"));

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("\"code\":\"EMAIL_TAKEN\"", "\"requestId\"");
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/register", body("not-an-email", "short", "Ergi"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body())
                .contains("\"code\":\"VALIDATION_FAILED\"")
                .contains("\"email\":\"Email is not valid\"")
                .contains("\"password\":\"Password must be 8 to 72 characters\"");
    }
}