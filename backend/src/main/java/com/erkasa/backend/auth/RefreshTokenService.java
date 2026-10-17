package com.erkasa.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates refresh tokens. The raw token goes to the client once; we keep only its SHA-256 hash.
 * Using it to get a new access token (with rotation) and logout come in sub-task 2.4.
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final JwtProperties props;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, JwtProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    /** A new token family (= a new login). Returns the raw token for the client. */
    @Transactional
    public String createForNewLogin(UUID userId) {
        return create(userId, UUID.randomUUID());
    }

    public long refreshTokenTtlSeconds() {
        return props.refreshTokenTtl().toSeconds();
    }

    private String create(UUID userId, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant expiresAt = Instant.now().plus(props.refreshTokenTtl());
        tokens.save(RefreshToken.create(userId, hash(raw), familyId, expiresAt));
        return raw;
    }

    /** SHA-256 as 64 hex characters. A fast hash is fine here: the token is already random. */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}