package io.github.sumansinghs71.idempotency.service;

import io.github.sumansinghs71.idempotency.api.ChargeRequest;
import io.github.sumansinghs71.idempotency.model.IdempotencyKey;
import io.github.sumansinghs71.idempotency.model.RecoveryPoint;
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

        // `instanceof` patterns rather than a switch over the sealed
        // interface: pattern matching in switch is still a preview feature on
        // Java 17, and enabling preview would pin every consumer — compiler,
        // test JVM, JMH's bytecode generator — to one exact JDK build. This is
        // the same dispatch with no language-preview dependency. AcquireResult
        // is sealed, so the compiler still guarantees the list is complete
        // whenever a new variant is added: the final `throw` is what fires.
        if (ar instanceof AcquireResult.Cached c) {
            meter.counter("idem.cache_hits.total").increment();
            meter.counter("idem.requests.total", "state", "cache_hit").increment();
            return IdempotencyOutcome.ok(c.statusCode(), c.body());
        }
        if (ar instanceof AcquireResult.Conflict c) {
            meter.counter("idem.requests.total", "state", "lock_conflict").increment();
            return IdempotencyOutcome.conflict(c.retryAfterMs());
        }
        if (ar instanceof AcquireResult.BodyMismatch) {
            meter.counter("idem.body_mismatch.total").increment();
            meter.counter("idem.requests.total", "state", "body_mismatch").increment();
            return IdempotencyOutcome.bodyMismatch();
        }
        if (ar instanceof AcquireResult.Fresh f) {
            meter.counter("idem.requests.total", "state", "new").increment();
            return runStateMachine(f.keyId(), RecoveryPoint.STARTED, canonicalBody);
        }
        if (ar instanceof AcquireResult.Resumed r) {
            meter.counter("idem.requests.total", "state", "resumed").increment();
            meter.counter("idem.recovery.total", "from_state",
                    r.recoveryPoint().dbValue()).increment();
            return runStateMachine(r.keyId(), r.recoveryPoint(), canonicalBody);
        }
        throw new IllegalStateException(
                "Unhandled AcquireResult variant: " + ar.getClass().getName());
    }

    private AcquireResult acquireWithConcurrentInsertRetry(
            long userId, String key, String method, String path,
            String canonicalBody, String bodyHash) {
        // A second attempt at acquireOrServe handles the two ways an INSERT can
        // hit the UNIQUE (user_id, key) index:
        //
        //   1. Two simultaneous brand-new INSERTs raced and we lost. The second
        //      attempt sees the winner's row and proceeds normally (conflict, or
        //      resume/cache-hit if the winner already finished).
        //   2. An EXPIRED row for this key is still in the index. findForUpdate
        //      filters on expires_at > now() so it is invisible to the read, but
        //      the index still enforces it. purgeExpiredKey commits its own
        //      delete so the second attempt can insert the replacement. In case
        //      (1) the row is not expired and the purge is a no-op.
        try {
            return phases.acquireOrServe(userId, key, method, path, canonicalBody, bodyHash);
        } catch (PhaseTransactions.ConcurrentInsertException retry) {
            phases.purgeExpiredKey(userId, key);
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
