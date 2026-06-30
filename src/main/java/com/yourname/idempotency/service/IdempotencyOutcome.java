package com.yourname.idempotency.service;

/** What {@link IdempotencyService#execute} returns to the controller. */
public record IdempotencyOutcome(int statusCode, String body, Long retryAfterMs) {

    public static IdempotencyOutcome ok(int code, String body) {
        return new IdempotencyOutcome(code, body, null);
    }

    public static IdempotencyOutcome conflict(long retryAfterMs) {
        return new IdempotencyOutcome(
                409,
                "{\"error\":\"idempotency_request_in_progress\",\"retry_after_ms\":"
                        + retryAfterMs + "}",
                retryAfterMs);
    }

    public static IdempotencyOutcome bodyMismatch() {
        return new IdempotencyOutcome(
                422, "{\"error\":\"idempotency_key_body_mismatch\"}", null);
    }
}
