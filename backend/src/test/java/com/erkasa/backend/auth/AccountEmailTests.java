package com.erkasa.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;

import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.erkasa.backend.AbstractIntegrationTest;
import com.erkasa.backend.common.mail.EmailMessage;

/**
 * Sub-task 2.6: email verification and password reset.
 *
 * Emails are sent in the background, so tests wait until the mocked EmailSender has received
 * the email for THEIR address (every test uses a unique address), then read the link out of it,
 * exactly like a user clicking it.
 */
class AccountEmailTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "secret123";
    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_\\-%]+)");
    private static final String VERIFY_SUBJECT = "Confirm your email";
    private static final String RESET_SUBJECT = "Reset your erkasa password";

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- helpers ----------

    private static String uniqueEmail() {
        return "mail-" + UUID.randomUUID() + "@example.com";
    }

    private String register(String email) throws Exception {
        HttpResponse<String> response = postJson("/api/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "Ergi"}
                """.formatted(email, PASSWORD));
        assertThat(response.statusCode()).isEqualTo(201);
        return email;
    }

    /** Emails "sent" to this address with this subject. Waits up to 5 s until there are at least {@code min}. */
    private List<EmailMessage> emailsTo(String to, String subject, int min) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            List<EmailMessage> found = mockingDetails(emailSender).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("send"))
                    .map(i -> (EmailMessage) i.getArgument(0))
                    .filter(m -> m.to().equals(to) && m.subject().contains(subject))
                    .toList();
            if (found.size() >= min || System.currentTimeMillis() > deadline) {
                return found;
            }
            Thread.sleep(50);
        }
    }

    /** Token from the newest matching email (waits for it). */
    private String tokenFromEmail(String to, String subject, int expectedCount) throws InterruptedException {
        List<EmailMessage> mails = emailsTo(to, subject, expectedCount);
        assertThat(mails).as("emails '%s' to %s", subject, to).hasSizeGreaterThanOrEqualTo(expectedCount);
        EmailMessage newest = mails.get(mails.size() - 1);
        Matcher m = TOKEN_IN_LINK.matcher(newest.text());
        assertThat(m.find()).as("link in email text").isTrue();
        return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
    }

    private String tokenFromEmail(String to, String subject) throws InterruptedException {
        return tokenFromEmail(to, subject, 1);
    }

    private HttpResponse<String> verifyEmail(String token) throws Exception {
        return postJson("/api/auth/verify-email", "{\"token\": \"" + token + "\"}");
    }

    private HttpResponse<String> forgotPassword(String email) throws Exception {
        return postJson("/api/auth/forgot-password", "{\"email\": \"" + email + "\"}");
    }

    private HttpResponse<String> resetPassword(String token, String newPassword) throws Exception {
        return postJson("/api/auth/reset-password", """
                {"token": "%s", "newPassword": "%s"}
                """.formatted(token, newPassword));
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return postJson("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(m.find()).as("field %s in %s", name, json).isTrue();
        return m.group(1);
    }

    private boolean emailVerifiedInDb(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select email_verified from users where email = ?", Boolean.class, email));
    }

    private static void assertInvalidLink(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_OR_EXPIRED_TOKEN\"");
    }

    // ---------- email verification ----------

    @Test
    void registrationSendsAVerificationEmailWithALink() throws Exception {
        String email = register(uniqueEmail());

        List<EmailMessage> mails = emailsTo(email, VERIFY_SUBJECT, 1);

        assertThat(mails).hasSize(1);
        EmailMessage mail = mails.get(0);
        assertThat(mail.text()).contains("http://localhost:5173/verify-email?token=");
        assertThat(mail.html()).contains("Confirm email", "verify-email?token=");
    }

    @Test
    void clickingTheLinkVerifiesTheEmail() throws Exception {
        String email = register(uniqueEmail());
        String token = tokenFromEmail(email, VERIFY_SUBJECT);
        assertThat(emailVerifiedInDb(email)).isFalse();

        assertThat(verifyEmail(token).statusCode()).isEqualTo(204);

        assertThat(emailVerifiedInDb(email)).isTrue();
    }

    @Test
    void aVerificationLinkWorksOnlyOnce() throws Exception {
        String email = register(uniqueEmail());
        String token = tokenFromEmail(email, VERIFY_SUBJECT);
        verifyEmail(token);

        assertInvalidLink(verifyEmail(token));
    }

    @Test
    void unknownVerificationTokenReturns400() throws Exception {
        assertInvalidLink(verifyEmail("not-a-real-token"));
    }

    @Test
    void expiredVerificationLinkReturns400() throws Exception {
        String email = register(uniqueEmail());
        String token = tokenFromEmail(email, VERIFY_SUBJECT);
        jdbc.update("update email_tokens set expires_at = now() - interval '1 minute' where token_hash = ?",
                RefreshTokenService.hash(token));

        assertInvalidLink(verifyEmail(token));
        assertThat(emailVerifiedInDb(email)).isFalse();
    }

    @Test
    void resendSendsANewLinkAndTheOldOneStopsWorking() throws Exception {
        String email = register(uniqueEmail());
        String oldToken = tokenFromEmail(email, VERIFY_SUBJECT, 1);
        String accessToken = field(login(email, PASSWORD).body(), "accessToken");

        HttpRequest resend = HttpRequest.newBuilder(uri("/api/auth/resend-verification"))
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertThat(http.send(resend, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(204);

        String newToken = tokenFromEmail(email, VERIFY_SUBJECT, 2);
        assertThat(newToken).isNotEqualTo(oldToken);

        assertInvalidLink(verifyEmail(oldToken));
        assertThat(verifyEmail(newToken).statusCode()).isEqualTo(204);
    }

    @Test
    void resendNeedsAnAccessToken() throws Exception {
        HttpRequest resend = HttpRequest.newBuilder(uri("/api/auth/resend-verification"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        assertThat(http.send(resend, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    // ---------- password reset ----------

    @Test
    void forgotPasswordForUnknownEmailReturns204AndSendsNothing() throws Exception {
        String nobody = uniqueEmail();

        assertThat(forgotPassword(nobody).statusCode()).isEqualTo(204);

        Thread.sleep(500);
        assertThat(emailsTo(nobody, "", 0)).isEmpty();
    }

    @Test
    void resetPasswordChangesThePasswordAndEndsAllSessions() throws Exception {
        String email = register(uniqueEmail());
        String oldRefreshToken = field(login(email, PASSWORD).body(), "refreshToken");

        assertThat(forgotPassword(email).statusCode()).isEqualTo(204);
        String token = tokenFromEmail(email, RESET_SUBJECT);

        assertThat(resetPassword(token, "brand-new-pass").statusCode()).isEqualTo(204);

        assertThat(login(email, PASSWORD).statusCode()).isEqualTo(401);
        assertThat(login(email, "brand-new-pass").statusCode()).isEqualTo(200);
        // The session from before the reset is gone.
        HttpResponse<String> refresh = postJson("/api/auth/refresh",
                "{\"refreshToken\": \"" + oldRefreshToken + "\"}");
        assertThat(refresh.statusCode()).isEqualTo(401);
        // Opening the reset link proved the user owns the email.
        assertThat(emailVerifiedInDb(email)).isTrue();
    }

    @Test
    void aResetLinkWorksOnlyOnce() throws Exception {
        String email = register(uniqueEmail());
        forgotPassword(email);
        String token = tokenFromEmail(email, RESET_SUBJECT);
        resetPassword(token, "brand-new-pass");

        assertInvalidLink(resetPassword(token, "another-pass-1"));
    }

    @Test
    void aVerificationTokenCannotBeUsedToResetThePassword() throws Exception {
        String email = register(uniqueEmail());
        String verifyToken = tokenFromEmail(email, VERIFY_SUBJECT);

        assertInvalidLink(resetPassword(verifyToken, "brand-new-pass"));
    }

    @Test
    void resetWithTooShortPasswordReturns400() throws Exception {
        HttpResponse<String> response = resetPassword("some-token", "short");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"newPassword\"");
    }

    @Test
    void forgotPasswordWithInvalidEmailReturns400() throws Exception {
        HttpResponse<String> response = forgotPassword("not-an-email");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"VALIDATION_FAILED\"", "\"email\"");
    }
}

