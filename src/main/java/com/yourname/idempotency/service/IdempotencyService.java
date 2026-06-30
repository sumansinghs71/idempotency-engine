package com.yourname.idempotency.service;

import com.yourname.idempotency.api.ChargeRequest;
import com.yourname.idempotency.model.IdempotencyKey;
import com.yourname.idempotency.model.RecoveryPoint;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the state machine. No {@code @Transactional} here on purpose —
 * each phase commits in its own {@link PhaseTransactions} method (which is
 * {@code REQUIRES_NEW}), and the external PSP call happens between phases,
 * outside any DB transaction.
 *
 * <p>Recovery sketch:
 * <pre>
 *   acquireOrServe        ← tx1
 *   while not FINISHED:
 *     switch recovery_point:
 *       STARTED              → runStartedPhase           ← tx2
 *       CUSTOMER_VALIDATED   → psp.charge(derivedKey)
 *                              persistExternalCallResult ← tx3
 *       EXTERNAL_API_CALLED  → runFinalizePhase          ← tx4
 *   return cached response
 * </pre>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final PhaseTransactions phases;
    private final ExternalPaymentClient psp;
    private final ChargeService chargeService;
    private final MeterRegistry meter;

    public IdempotencyService(
            PhaseTransactions phases,
            ExternalPaymentClient psp,
            ChargeService chargeService,
            MeterRegistry meter) {
        this.phases = phases;
        this.psp = psp;
        this.chargeService = chargeService;
        this.meter = meter;
    }

    public IdempotencyOutcome execute(
            long userId, String key, String method, String path,
            String canonicalBody, String bodyHash) {

        long t0 = System.nanoTime();
        AcquireResult ar = acquireWithConcurrentInsertRetry(
                userId, key, method, path, canonicalBody, bodyHash);

        meter.timer("idem.lock_wait.duration")
                .record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);

        return switch (ar) {
            case AcquireResult.Cached c -> {
                meter.counter("idem.cache_hits.total").increment();
                meter.counter("idem.requests.total", "state", "cache_hit").increment();
                yield IdempotencyOutcome.ok(c.statusCode(), c.body());
            }
            case AcquireResult.Conflict c -> {
                meter.counter("idem.requests.total", "state", "lock_conflict").increment();
                yield IdempotencyOutcome.conflict(c.retryAfterMs());
            }
            case AcquireResult.BodyMismatch ignored -> {
                meter.counter("idem.body_mismatch.total").increment();
                meter.counter("idem.requests.total", "state", "body_mismatch").increment();
                yield IdempotencyOutcome.bodyMismatch();
            }
            case AcquireResult.Fresh f -> {
                meter.counter("idem.requests.total", "state", "new").increment();
                yield runStateMachine(f.keyId(), RecoveryPoint.STARTED, canonicalBody);
            }
            case AcquireResult.Resumed r -> {
                meter.counter("idem.requests.total", "state", "resumed").increment();
                meter.counter("idem.recovery.total", "from_state",
                        r.recoveryPoint().dbValue()).increment();
                yield runStateMachine(r.keyId(), r.recoveryPoint(), canonicalBody);
            }
        };
    }

    private AcquireResult acquireWithConcurrentInsertRetry(
            long userId, String key, String method, String path,
            String canonicalBody, String bodyHash) {
        // A second attempt at acquireOrServe handles the rare case where two
        // simultaneous brand-new INSERTs raced and we lost. The second attempt
        // will see the winner's row and proceed normally (either conflict, or
        // resume if the winner already finished).
        try {
            return phases.acquireOrServe(userId, key, method, path, canonicalBody, bodyHash);
        } catch (PhaseTransactions.ConcurrentInsertException retry) {
            return phases.acquireOrServe(userId, key, method, path, canonicalBody, bodyHash);
        }
    }

    private IdempotencyOutcome runStateMachine(
            long keyId, RecoveryPoint startFrom, String canonicalBody) {
        RecoveryPoint rp = startFrom;
        int loopGuard = 0;
        try {
            while (rp != RecoveryPoint.FINISHED) {
                if (++loopGuard > 16) {
                    throw new IllegalStateException(
                            "state machine loop guard exceeded; rp=" + rp);
                }
                rp = switch (rp) {
                    case STARTED -> phases.runStartedPhase(keyId);
                    case CUSTOMER_VALIDATED -> runExternalCallPhase(keyId, canonicalBody);
                    case EXTERNAL_API_CALLED -> phases.runFinalizePhase(keyId);
                    case FINISHED -> RecoveryPoint.FINISHED;
                };
            }
        } catch (RuntimeException e) {
            log.error("Phase failure for key {}: {}", keyId, e.toString());
            phases.releaseLockOnError(keyId);
            throw e;
        }
        IdempotencyKey snap = phases.snapshot(keyId);
        return IdempotencyOutcome.ok(snap.getResponseCode(), snap.getResponseBody());
    }

    private RecoveryPoint runExternalCallPhase(long keyId, String canonicalBody) {
        // The PSP call sits OUTSIDE any DB transaction. We compute the derived
        // key from the inbound row id so every retry of the same logical
        // request constructs the same derived key. The PSP dedups on it.
        IdempotencyKey snap = phases.snapshot(keyId);
        ChargeRequest req = chargeService.parse(snap.getRequestBody());

        String derivedKey = "idem-" + keyId;
        long t0 = System.nanoTime();
        PspChargeResult result;
        try {
            result = psp.charge(
                    req.amount(), req.currency(), req.customerId(), derivedKey);
        } catch (RuntimeException e) {
            meter.timer("idem.external_call.duration", "outcome", "error")
                    .record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            // We do NOT commit a recovery point here. The next retry will see
            // CUSTOMER_VALIDATED and re-issue with the same derived key.
            throw e;
        }
        meter.timer("idem.external_call.duration", "outcome",
                result.outcome().name().toLowerCase())
                .record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);

        return phases.persistExternalCallResult(keyId, result, derivedKey);
    }
}
