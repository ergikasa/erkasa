package com.erkasa.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

import com.erkasa.backend.AbstractIntegrationTest;

/**
 * Uses the REAL GoogleIdTokenVerifier. Tests run without GOOGLE_CLIENT_ID, so Google login
 * must answer with a clear 503 instead of crashing or calling Google.
 */
class GoogleNotConfiguredTests extends AbstractIntegrationTest {

    @Test
    void googleLoginWithoutClientIdReturns503() throws Exception {
        HttpResponse<String> response = postJson("/api/auth/google", "{\"idToken\": \"anything\"}");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"code\":\"GOOGLE_LOGIN_NOT_CONFIGURED\"");
    }
}