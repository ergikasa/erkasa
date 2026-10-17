package com.erkasa.backend.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.erkasa.backend.common.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes 401 and 403 responses in the same JSON shape as GlobalExceptionHandler.
 *
 * Needed because security checks run in a filter, before the request reaches any controller,
 * so @RestControllerAdvice never sees these errors.
 */
@Component
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    /** No token, a wrong token, or an expired token. */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, request, 401, "Unauthorized", "UNAUTHORIZED",
                "Missing, invalid or expired access token");
    }

    /** Valid token, but the user's role is not allowed here. */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        write(response, request, 403, "Forbidden", "FORBIDDEN",
                "You do not have permission to do this");
    }

    private static void write(HttpServletResponse response,
                              HttpServletRequest request,
                              int status,
                              String title,
                              String code,
                              String detail) throws IOException {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        String json = "{"
                + "\"type\":\"about:blank\","
                + "\"title\":\"" + escape(title) + "\","
                + "\"status\":" + status + ","
                + "\"detail\":\"" + escape(detail) + "\","
                + "\"instance\":\"" + escape(request.getRequestURI()) + "\","
                + "\"code\":\"" + escape(code) + "\""
                + (requestId != null ? ",\"requestId\":\"" + escape(requestId) + "\"" : "")
                + "}";

        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json);
    }

    /** Minimal JSON string escaping for the few values we write. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}