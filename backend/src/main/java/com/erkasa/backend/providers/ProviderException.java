package com.erkasa.backend.providers;


/**
 * A provider call failed.
 *
 * retryable = true for problems that often go away on their own (timeout, 429 rate limit,
 * 5xx server error). retryable = false for problems a retry cannot fix (bad API key,
 * content refused by safety filters, invalid request).
 */
public class ProviderException extends RuntimeException {

    private final String provider;
    private final String code;
    private final boolean retryable;

    public ProviderException(String provider, String code, String message, boolean retryable, Throwable cause) {
        super(provider + " " + code + ": " + message, cause);
        this.provider = provider;
        this.code = code;
        this.retryable = retryable;
    }

    /** Classifies an HTTP error status from a provider API. */
    public static ProviderException fromHttpStatus(String provider, int status, String body) {
        boolean retryable = status == 408 || status == 429 || status >= 500;
        String code = switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401, 403 -> "AUTH_FAILED";
            case 408 -> "TIMEOUT";
            case 429 -> "RATE_LIMITED";
            default -> status >= 500 ? "PROVIDER_ERROR" : "HTTP_" + status;
        };
        String shortBody = body == null ? "" : body.substring(0, Math.min(body.length(), 300));
        return new ProviderException(provider, code, "HTTP " + status + " " + shortBody, retryable, null);
    }

    public String getProvider() {
        return provider;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}