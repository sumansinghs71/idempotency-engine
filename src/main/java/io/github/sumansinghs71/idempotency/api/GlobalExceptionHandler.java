package io.github.sumansinghs71.idempotency.api;

import io.github.sumansinghs71.idempotency.service.FakeExternalPaymentClient;
import io.github.sumansinghs71.idempotency.service.PhaseTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FakeExternalPaymentClient.TransientPspException.class)
    public ResponseEntity<ErrorResponse> handleTransientPsp(
            FakeExternalPaymentClient.TransientPspException e) {
        log.warn("Transient PSP failure: {}", e.getMessage());
        return ResponseEntity.status(503).body(
                ErrorResponse.of("temporarily_unavailable", e.getMessage()));
    }

    @ExceptionHandler(PhaseTransactions.UnknownReferenceException.class)
    public ResponseEntity<ErrorResponse> handleUnknownReference(
            PhaseTransactions.UnknownReferenceException e) {
        log.warn("Unknown reference: {}", e.getMessage());
        return ResponseEntity.status(404).body(
                ErrorResponse.of("unknown_user", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("bad_request", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleUnknown(RuntimeException e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(500).body(ErrorResponse.of("internal"));
    }
}
