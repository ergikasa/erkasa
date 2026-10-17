package com.erkasa.backend.auth;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.erkasa.backend.common.error.ProblemJsonWriter;

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
        ProblemJsonWriter.write(response, request.getRequestURI(), 401, "Unauthorized", "UNAUTHORIZED",
                "Missing, invalid or expired access token");
    }

    /** Valid token, but the user's role is not allowed here. */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        ProblemJsonWriter.write(response, request.getRequestURI(), 403, "Forbidden", "FORBIDDEN",
                "You do not have permission to do this");
    }
}