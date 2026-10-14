package com.erkasa.backend.common.error;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.erkasa.backend.common.web.RequestIdFilter;

/**
 * Turns every error into the same JSON shape (RFC 9457 "Problem Details"):
 *
 * {
 *   "type": "about:blank",
 *   "title": "Not Found",
 *   "status": 404,
 *   "detail": "...",
 *   "instance": "/api/...",
 *   "code": "NOT_FOUND",
 *   "requestId": "..."
 * }
 *
 * Spring's own errors (404, validation, wrong method, bad JSON) are handled by the parent class.
 * We add "code" and "requestId" to all of them.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Errors our own code throws on purpose. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        body.setProperty("code", ex.getCode());
        return createResponseEntity(body, new HttpHeaders(), ex.getStatus(), request);
    }

    /** Anything unexpected: log the full error, but never show internal details to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error", ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong. Please try again, and quote the requestId if it keeps happening.");
        body.setProperty("code", "INTERNAL_ERROR");
        return createResponseEntity(body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /** Every error response passes through here: add requestId, and a code if missing. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body,
                                                          HttpHeaders headers,
                                                          HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                problem.setProperty("code", defaultCode(statusCode));
            }
            String requestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (requestId != null) {
                problem.setProperty("requestId", requestId);
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /** 404 -> NOT_FOUND, 400 -> BAD_REQUEST, 405 -> METHOD_NOT_ALLOWED, ... */
    private static String defaultCode(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "HTTP_" + statusCode.value();
    }
}