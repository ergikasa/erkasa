package com.erkasa.backend.auth;

import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.erkasa.backend.common.error.ApiException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Creates and checks access tokens (JWT, signed with HMAC-SHA256).
 *
 * A JWT is three base64 parts: header.claims.signature. Anyone can read the claims,
 * but only someone with our secret can produce a valid signature, so the backend can
 * trust a token without looking anything up in the database.
 */
@Service
@EnableConfigurationProperties(JwtProperties.class)
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties props;
    private final byte[] secret;

    public JwtService(JwtProperties props) {
        this.props = props;
        this.secret = decodeSecret(props.secret());
    }

    /** A signed access token for this user, valid for erkasa.jwt.access-token-ttl. */
    public String createAccessToken(User user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(props.accessTokenTtl())))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign access token", e);
        }
    }

    /** Checks signature, issuer and expiry. Returns the claims, or throws 401 INVALID_TOKEN. */
    public JWTClaimsSet verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new MACVerifier(secret))) {
                throw invalidToken();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiry = claims.getExpirationTime();
            if (!props.issuer().equals(claims.getIssuer())
                    || expiry == null
                    || expiry.toInstant().isBefore(Instant.now())) {
                throw invalidToken();
            }
            return claims;
        } catch (ParseException | JOSEException e) {
            throw invalidToken();
        }
    }

    public long accessTokenTtlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }

    private static byte[] decodeSecret(String base64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException(
                    "erkasa.jwt.secret is missing or not valid base64. Set JWT_SECRET in infra/.env", e);
        }
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "erkasa.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes (got " + bytes.length + ")");
        }
        return bytes;
    }

    private static ApiException invalidToken() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Token is invalid or expired");
    }
}