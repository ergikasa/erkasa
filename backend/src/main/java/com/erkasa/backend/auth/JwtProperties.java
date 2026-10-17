package com.erkasa.backend.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token settings from the "erkasa.jwt" section of application.yml.
 *
 * @param secret          base64 key used to sign access tokens (at least 32 bytes), from JWT_SECRET
 * @param issuer          written into every token, checked when reading it
 * @param accessTokenTtl  how long an access token is valid (short: 15 minutes)
 * @param refreshTokenTtl how long a refresh token is valid (long: 30 days)
 */
@ConfigurationProperties(prefix = "erkasa.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl
) {
}