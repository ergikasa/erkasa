package com.erkasa.backend.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.erkasa.backend.common.web.RequestIdFilter;
import com.erkasa.backend.features.promptvideo.project.InvalidScriptException;
import com.erkasa.backend.jobs.InvalidTransitionException;
import com.erkasa.backend.providers.ProviderException;

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

    /**
     * Invalid request body (@Valid failed). Adds the message for each wrong field:
     * "code": "VALIDATION_FAILED", "errors": {"email": "Email is not valid"}
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "Some fields are not valid");
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return createResponseEntity(body, headers, status, request);
    }

    /**
     * An AI provider failed (after retries). 502 = "a service we depend on failed", not our bug.
     * The client sees which provider and why (e.g. fal / NO_IMAGE); the provider's raw answer
     * stays in the log only.
     */
    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<Object> handleProviderException(ProviderException ex, WebRequest request) {
        log.warn("Provider failed: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The " + ex.getProvider() + " service failed (" + ex.getCode() + ").");
        body.setProperty("code", "PROVIDER_FAILED");
        body.setProperty("provider", ex.getProvider());
        body.setProperty("providerCode", ex.getCode());
        body.setProperty("retryable", ex.isRetryable());
        return createResponseEntity(body, new HttpHeaders(), HttpStatus.BAD_GATEWAY, request);
    }

    /** A status change that is not allowed now, e.g. approving a script twice. 409 Conflict. */
    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<Object> handleInvalidTransition(InvalidTransitionException ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This action is not possible while the project is " + ex.getFrom() + ".");
        body.setProperty("code", "INVALID_STATE");
        body.setProperty("currentStatus", ex.getFrom());
        return createResponseEntity(body, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /** An edited script breaks the rules. 422 with the list of problems for the UI to show. */
    @ExceptionHandler(InvalidScriptException.class)
    public ResponseEntity<Object> handleInvalidScript(InvalidScriptException ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(422),
                "The script is not valid");
        body.setProperty("code", "INVALID_SCRIPT");
        body.setProperty("problems", ex.getProblems());
        return createResponseEntity(body, new HttpHeaders(), HttpStatus.valueOf(422), request);
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