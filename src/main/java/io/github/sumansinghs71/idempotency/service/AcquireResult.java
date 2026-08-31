package io.github.sumansinghs71.idempotency.service;

import io.github.sumansinghs71.idempotency.model.RecoveryPoint;

/** Outcome of acquiring the key row at the top of the request lifecycle. */
public sealed interface AcquireResult {

    /** Fresh row — we own the lock; start at {@link RecoveryPoint#STARTED}. */
    record Fresh(long keyId) implements AcquireResult {}

    /** Existing row reclaimed (stale lock or unlocked non-finished). */
    record Resumed(long keyId, RecoveryPoint recoveryPoint) implements AcquireResult {}

    /** Existing finished row — serve cached response. */
    record Cached(int statusCode, String body) implements AcquireResult {}

    /** Another holder is alive; client should retry after backoff. */
    record Conflict(long retryAfterMs) implements AcquireResult {}

    /** Body fingerprint mismatch — client bug. */
    record BodyMismatch() implements AcquireResult {}
}
