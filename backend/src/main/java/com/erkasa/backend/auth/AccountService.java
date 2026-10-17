package com.erkasa.backend.auth;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erkasa.backend.common.mail.EmailRequestedEvent;

/** Email verification and password reset. */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    static final Duration VERIFY_EMAIL_TTL = Duration.ofHours(24);
    static final Duration RESET_PASSWORD_TTL = Duration.ofHours(1);

    private final UserRepository users;
    private final EmailTokenService emailTokens;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AccountEmails emails;
    private final ApplicationEventPublisher events;

    public AccountService(UserRepository users,
                          EmailTokenService emailTokens,
                          RefreshTokenService refreshTokens,
                          PasswordEncoder passwordEncoder,
                          AccountEmails emails,
                          ApplicationEventPublisher events) {
        this.users = users;
        this.emailTokens = emailTokens;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.emails = emails;
        this.events = events;
    }

    /** Sends a "confirm your email" link. Does nothing if the email is already verified. */
    @Transactional
    public void sendVerificationEmail(User user) {
        if (user.isEmailVerified()) {
            return;
        }
        String token = emailTokens.create(user.getId(), EmailTokenPurpose.VERIFY_EMAIL, VERIFY_EMAIL_TTL);
        events.publishEvent(new EmailRequestedEvent(emails.verifyEmail(user, token)));
    }

    /** The user clicked the link in the email. */
    @Transactional
    public void verifyEmail(String token) {
        UUID userId = emailTokens.consume(token, EmailTokenPurpose.VERIFY_EMAIL);
        User user = users.findById(userId).orElseThrow(EmailTokenService::invalid);
        user.markEmailVerified();
        log.info("Email verified for user {}", userId);
    }

    /** The logged-in user asks for a new verification link. */
    @Transactional
    public void resendVerificationEmail(UUID userId) {
        users.findById(userId).ifPresent(this::sendVerificationEmail);
    }

    /**
     * Sends a reset link if an account with this email exists.
     * The caller always gets the same answer, so nobody can find out which emails have accounts.
     * Also works for Google-only users: it lets them add a password.
     */
    @Transactional
    public void forgotPassword(String email) {
        users.findByEmail(AuthService.normalizeEmail(email)).ifPresent(user -> {
            String token = emailTokens.create(user.getId(), EmailTokenPurpose.RESET_PASSWORD, RESET_PASSWORD_TTL);
            events.publishEvent(new EmailRequestedEvent(emails.resetPassword(user, token)));
            log.info("Password reset requested for user {}", user.getId());
        });
    }

    /**
     * Sets the new password from the reset link.
     * - The email counts as verified: the user just proved they can read it.
     * - Every session is ended: if the password was reset because of a break-in, the intruder is out.
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        UUID userId = emailTokens.consume(token, EmailTokenPurpose.RESET_PASSWORD);
        User user = users.findById(userId).orElseThrow(EmailTokenService::invalid);
        user.changePassword(passwordEncoder.encode(newPassword));
        user.markEmailVerified();
        int sessions = refreshTokens.revokeAllForUser(userId);
        log.info("Password reset for user {} ({} sessions ended)", userId, sessions);
    }
}