package com.erkasa.backend.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erkasa.backend.common.error.ApiException;

/** Creates and consumes the one-time tokens that go into emailed links. */
@Service
public class EmailTokenService {

    private static final int TOKEN_BYTES = 32;

    private final EmailTokenRepository tokens;
    private final SecureRandom random = new SecureRandom();

    public EmailTokenService(EmailTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** A new link token. Any older unused token of the same purpose stops working. Returns the raw token. */
    @Transactional
    public String create(UUID userId, EmailTokenPurpose purpose, Duration ttl) {
        Instant now = Instant.now();
        tokens.invalidateActive(userId, purpose, now);

        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        tokens.save(EmailToken.create(userId, purpose, RefreshTokenService.hash(raw), now.plus(ttl)));
        return raw;
    }

    /**
     * Uses the token once. Returns the user it belongs to.
     * Unknown, already used, expired, or meant for the other purpose: 400 INVALID_OR_EXPIRED_TOKEN.
     */
    @Transactional
    public UUID consume(String rawToken, EmailTokenPurpose purpose) {
        Instant now = Instant.now();
        EmailToken token = tokens.findForUpdate(RefreshTokenService.hash(rawToken), purpose)
                .filter(t -> t.isUsable(now))
                .orElseThrow(EmailTokenService::invalid);
        token.markUsed(now);
        return token.getUserId();
    }

    static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_TOKEN",
                "This link is invalid or has expired. Please request a new one");
    }
}