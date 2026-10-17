package com.erkasa.backend.common.web;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "erkasa.rate-limit" in application.yml.
 *
 * @param enabled    turn the whole thing off (tests do this)
 * @param trustProxy true only when a reverse proxy (nginx, Cloudflare) sits in front and sets
 *                   X-Forwarded-For. If this were true without such a proxy, anyone could send a
 *                   fake header and get a fresh limit on every request.
 * @param rules      which requests are limited, and how much
 */
@ConfigurationProperties(prefix = "erkasa.rate-limit")
public record RateLimitProperties(boolean enabled, boolean trustProxy, List<Rule> rules) {

    public RateLimitProperties {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * @param name   short id, also used as part of the counter key
     * @param method HTTP method, e.g. POST
     * @param path   exact path, e.g. /api/auth/login
     * @param limit  how many requests one IP may send within the window
     * @param window how long the window lasts, e.g. 5m or 1h
     */
    public record Rule(String name, String method, String path, int limit, Duration window) {

        public boolean matches(String requestMethod, String requestPath) {
            return method.equalsIgnoreCase(requestMethod) && path.equals(requestPath);
        }
    }
}