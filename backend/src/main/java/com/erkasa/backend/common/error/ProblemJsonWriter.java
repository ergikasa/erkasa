package com.erkasa.backend.common.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.MDC;

import com.erkasa.backend.common.web.RequestIdFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes an error response in the same JSON shape as GlobalExceptionHandler (RFC 9457):
 *
 * {"type":"about:blank","title":"...","status":429,"detail":"...","instance":"/api/...",
 *  "code":"...","requestId":"..."}
 *
 * Used by the parts that run in filters, before any controller, where @RestControllerAdvice
 * cannot see the error: security (401/403) and rate limiting (429).
 */
public final class ProblemJsonWriter {

    private ProblemJsonWriter() {
    }

    public static void write(HttpServletResponse response,
                             String instance,
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
                + "\"instance\":\"" + escape(instance) + "\","
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