package com.erkasa.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.google" in application.yml.
 *
 * @param clientId the OAuth Client ID from Google Cloud Console. Not a secret (the frontend shows it),
 *                 but it must match: we only accept tokens Google issued for THIS client.
 */
@ConfigurationProperties(prefix = "erkasa.google")
public record GoogleProperties(String clientId) {
}