package com.erkasa.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erkasa.backend.common.error.ApiException;

/**
 * Refresh tokens: create, rotate, revoke.
 *
 * The raw token goes to the client once; we keep only its SHA-256 hash.
 * Every token belongs to a "family" (one login = one family = one device session).
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository tokens;
    private final JwtProperties props;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository tokens, JwtProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    /** Result of a successful rotation: who the user is, and the new raw token for the client. */
    public record Rotation(UUID userId, String newRefreshToken) {
    }

    /** A new token family (= a new login). Returns the raw token for the client. */
    @Transactional
    public String createForNewLogin(UUID userId) {
        return create(userId, UUID.randomUUID()).raw();
    }

    /**
     * Exchanges a refresh token for a new one (rotation).
     *
     * - Unknown or expired token: 401.
     * - Already used token: someone is replaying it, so it was probably stolen. We end the whole
     *   session (the thief's copy and the real user's newer token) and return 401.
     *
     * noRollbackFor: when we detect reuse we revoke the family AND throw. Without this, the
     * exception would roll back the transaction and undo the revocation.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        Instant now = Instant.now();
        RefreshToken current = tokens.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(RefreshTokenService::invalid);

        if (current.isRevoked()) {
            int revoked = tokens.revokeFamily(current.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}: session ended ({} tokens revoked)",
                    current.getUserId(), revoked);
            throw invalid();
        }
        if (current.isExpired(now)) {
            throw invalid();
        }

        Created next = create(current.getUserId(), current.getFamilyId());
        current.markReplacedBy(next.id(), now);
        return new Rotation(current.getUserId(), next.raw());
    }

    /**
     * Logout on this device: ends the session the token belongs to.
     * Says nothing about whether the token existed, so it cannot be used to probe tokens.
     */
    @Transactional
    public void revokeSession(String rawToken) {
        tokens.findByTokenHash(hash(rawToken))
                .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), Instant.now()));
    }

    /** Logout on all devices. */
    @Transactional
    public int revokeAllForUser(UUID userId) {
        return tokens.revokeAllForUser(userId, Instant.now());
    }

    public long refreshTokenTtlSeconds() {
        return props.refreshTokenTtl().toSeconds();
    }

    private record Created(UUID id, String raw) {
    }

    private Created create(UUID userId, UUID familyId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Instant expiresAt = Instant.now().plus(props.refreshTokenTtl());
        RefreshToken saved = tokens.save(RefreshToken.create(userId, hash(raw), familyId, expiresAt));
        return new Created(saved.getId(), raw);
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

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                "Session expired. Please log in again");
    }
}