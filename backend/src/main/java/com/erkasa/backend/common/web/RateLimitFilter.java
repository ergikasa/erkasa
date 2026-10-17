package com.erkasa.backend.common.web;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.erkasa.backend.common.error.ProblemJsonWriter;
import com.erkasa.backend.common.web.RateLimitProperties.Rule;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects a caller who sends too many requests to a limited endpoint.
 *
 * Runs before Spring Security on purpose: checking a password costs a deliberately slow BCrypt
 * hash, so an attacker must be stopped before we spend that work.
 *
 * Requests with no matching rule pass straight through.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10) // after RequestIdFilter, so the error carries a requestId
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitProperties props;
    private final RateLimiter limiter;

    public RateLimitFilter(RateLimitProperties props, RateLimiter limiter) {
        this.props = props;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Rule rule = matchingRule(request);
        if (rule == null) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = clientIp(request);
        RateLimiter.Decision decision = limiter.check(rule.name() + "|" + clientIp, rule.limit(), rule.window());

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("Rate limit hit: rule={} ip={}", rule.name(), clientIp);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        ProblemJsonWriter.write(response, request.getRequestURI(), 429, "Too Many Requests", "RATE_LIMITED",
                "Too many requests. Please try again in " + decision.retryAfterSeconds() + " seconds");
    }

    /** Skip the whole filter when rate limiting is off. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.enabled();
    }

    private Rule matchingRule(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return props.rules().stream()
                .filter(r -> r.matches(method, path))
                .findFirst()
                .orElse(null);
    }

    /**
     * Behind a reverse proxy every request seems to come from the proxy, so the real address is
     * in X-Forwarded-For ("client, proxy1, proxy2"); the first entry is the client.
     * Only trusted when erkasa.rate-limit.trust-proxy is true, otherwise anyone could fake it.
     */
    private String clientIp(HttpServletRequest request) {
        if (props.trustProxy()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String first = forwarded.split(",")[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}