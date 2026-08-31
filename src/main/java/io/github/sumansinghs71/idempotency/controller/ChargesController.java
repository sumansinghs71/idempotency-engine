package io.github.sumansinghs71.idempotency.controller;

import io.github.sumansinghs71.idempotency.interceptor.IdempotencyInterceptor;
import io.github.sumansinghs71.idempotency.service.IdempotencyOutcome;
import io.github.sumansinghs71.idempotency.service.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin façade — all real work happens in {@link IdempotencyService}.
 */
@RestController
public class ChargesController {

    private final IdempotencyService idempotencyService;

    public ChargesController(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @PostMapping(value = "/charges", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(HttpServletRequest request) {
        long userId = (long) request.getAttribute(IdempotencyInterceptor.ATTR_USER_ID);
        String key = (String) request.getAttribute(IdempotencyInterceptor.ATTR_KEY);
        String body = (String) request.getAttribute(IdempotencyInterceptor.ATTR_CANONICAL_BODY);
        String bodyHash = (String) request.getAttribute(IdempotencyInterceptor.ATTR_BODY_HASH);

        IdempotencyOutcome outcome =
                idempotencyService.execute(userId, key, "POST", "/charges", body, bodyHash);

        ResponseEntity.BodyBuilder b = ResponseEntity.status(outcome.statusCode());
        if (outcome.retryAfterMs() != null) {
            b.header("Retry-After-Ms", String.valueOf(outcome.retryAfterMs()));
        }
        return b.contentType(MediaType.APPLICATION_JSON).body(outcome.body());
    }
}
