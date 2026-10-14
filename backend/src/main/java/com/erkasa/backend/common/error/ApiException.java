package com.erkasa.backend.common.error;



import org.springframework.http.HttpStatus;

/**
 * The exception our own code throws when a request cannot be completed.
 * Example: throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_CREDITS", "Not enough credits");
 *
 * - status:  the HTTP status returned to the client
 * - code:    a stable, machine-readable code the frontend can switch on (never changes)
 * - message: a human-readable explanation
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}