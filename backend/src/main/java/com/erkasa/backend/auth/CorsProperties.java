package com.erkasa.backend.auth;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which websites may call the API from a browser ("erkasa.cors" in application.yml).
 * Locally the Vite dev server; in production the real domain, e.g. https://erkasa.com
 */
@ConfigurationProperties(prefix = "erkasa.cors")
public record CorsProperties(List<String> allowedOrigins) {
}