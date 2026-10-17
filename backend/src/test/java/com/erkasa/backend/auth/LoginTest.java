package com.erkasa.backend.auth;



import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.common.error.ApiException;
import com.nimbusds.jwt.JWTClaimsSet;

/** Sub-task 2.2: POST /api/auth/login */
class LoginTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "secret123";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    /** Registers a fresh user and returns its email. */
    private String registerUser() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        HttpResponse<String> response = postJson("/api/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "Ergi"}
                """.formatted(email, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return email;
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return postJson("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    /** Reads one string field from a flat JSON response, e.g. "accessToken". */
    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("field %s in %s", name, json).isTrue();
        return m.group(1);
    }

    @Test
    void correctPasswordReturnsTokens() throws Exception {
        String email = registerUser();

        HttpResponse<String> response = login(email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"tokenType\":\"Bearer\"")
                .contains("\"expiresIn\":900")
                .contains("\"email\":\"" + email + "\"")
                .doesNotContain("passwordHash");
    }

    @Test
    void accessTokenIsAValidJwtForThisUser() throws Exception {
        String email = registerUser();

        String accessToken = field(login(email, PASSWORD).body(), "accessToken");
        JWTClaimsSet claims = jwtService.verify(accessToken);

        String userId = jdbc.queryForObject("select id::text from users where email = ?", String.class, email);
        assertThat(claims.getSubject()).isEqualTo(userId);
        assertThat(claims.getStringClaim("email")).isEqualTo(email);
        assertThat(claims.getStringClaim("role")).isEqualTo("USER");
        assertThat(claims.getIssuer()).isEqualTo("erkasa");
    }

    @Test
    void tamperedAccessTokenIsRejected() throws Exception {
        String email = registerUser();
        String accessToken = field(login(email, PASSWORD).body(), "accessToken");

        // Change one character inside the signature (last part of the JWT).
        // Not the very last character: its lowest bits are padding and may be ignored.
        int i = accessToken.length() - 10;
        char c = accessToken.charAt(i);
        String tampered = accessToken.substring(0, i) + (c == 'A' ? 'B' : 'A') + accessToken.substring(i + 1);

        assertThatThrownBy(() -> jwtService.verify(tampered))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void refreshTokenIsStoredOnlyAsHash() throws Exception {
        String email = registerUser();

        String refreshToken = field(login(email, PASSWORD).body(), "refreshToken");

        String storedHash = jdbc.queryForObject("""
                select t.token_hash from refresh_tokens t
                join users u on u.id = t.user_id
                where u.email = ?
                """, String.class, email);
        assertThat(storedHash)
                .isNotEqualTo(refreshToken)
                .isEqualTo(RefreshTokenService.hash(refreshToken))
                .hasSize(64);
    }

    @Test
    void emailIsCaseInsensitive() throws Exception {
        String email = registerUser();

        assertThat(login(email.toUpperCase(), PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        String email = registerUser();

        HttpResponse<String> response = login(email, "wrong-password");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    @Test
    void unknownEmailReturnsTheSame401() throws Exception {
        HttpResponse<String> response = login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
    }

    @Test
    void missingPasswordReturns400() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/login", """
                {"email": "someone@example.com"}
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"password\"");
    }
}