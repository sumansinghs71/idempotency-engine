package io.github.sumansinghs71.idempotency.api;

public record ErrorResponse(String error, String message) {
    public static ErrorResponse of(String code) {
        return new ErrorResponse(code, null);
    }
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message);
    }
}
