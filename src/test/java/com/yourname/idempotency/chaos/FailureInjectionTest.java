package com.yourname.idempotency.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yourname.idempotency.AbstractPostgresIT;
import com.yourname.idempotency.model.IdempotencyKey;
import com.yourname.idempotency.model.RecoveryPoint;
import com.yourname.idempotency.model.RequestHash;
import com.yourname.idempotency.repository.IdempotencyKeyRepository;
import com.yourname.idempotency.repository.RideRepository;
import com.yourname.idempotency.service.FakeExternalPaymentClient;
import com.yourname.idempotency.service.IdempotencyOutcome;
import com.yourname.idempotency.service.IdempotencyService;
import com.yourname.idempotency.service.PhaseTransactions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 8 — Tests 5 and 6: the chaos tests.
 *
 * <p>Failure injection here is in-process: we set hooks on
 * {@link FakeExternalPaymentClient} to throw at specific points and assert
 * that the retry path converges to exactly one PSP charge.
 *
 * <p>Toxiproxy-based network-layer chaos is the recommended next step for a
 * real environment and is wired in via docker-compose; this in-process suite
 * proves the recovery logic itself.
 */
class FailureInjectionTest extends AbstractPostgresIT {

    @Autowired IdempotencyService service;
    @Autowired PhaseTransactions phases;
    @Autowired FakeExternalPaymentClient psp;
    @Autowired IdempotencyKeyRepository keyRepo;
    @Autowired RideRepository rideRepo;
    @PersistenceContext EntityManager em;

    private long userId;
    private static final String BODY =
            "{\"amount\":2000,\"currency\":\"usd\",\"customer_id\":\"cus_chaos\"}";

    @BeforeEach
    void setUp() {
        psp.resetCounters();
        psp.clearForcedDecline();
        keyRepo.deleteAll();
        userId = seedUser("chaos-" + UUID.randomUUID() + "@example.com");
    }

    /**
     * F6 — the critical case. The PSP commits the charge, then the response
     * packet (effectively the JVM-side) dies before tx3 can commit. On retry,
     * the PSP returns the same charge_id from its own idempotency cache. The
     * customer must be charged exactly once.
     */
    @Test
    void testCrashDuringExternalApiCallNoDoubleCharge() {
        String key = UUID.randomUUID().toString();
        String canonical = RequestHash.canonicalize(BODY);
        String hash = RequestHash.sha256OfCanonicalized(BODY);

        psp.failNextCallAfterCharging();

        assertThatThrownBy(() -> service.execute(
                userId, key, "POST", "/charges", canonical, hash))
                .isInstanceOf(FakeExternalPaymentClient.TransientPspException.class);

        // After the failure, the row should be at CUSTOMER_VALIDATED (tx2
        // committed; tx3 never opened), lock released (releaseLockOnError),
        // and the PSP should have recorded exactly one charge.
        IdempotencyKey row = keyRepo.findByUserIdAndKey(userId, key).orElseThrow();
        assertThat(row.recoveryPointEnum()).isEqualTo(RecoveryPoint.CUSTOMER_VALIDATED);
        assertThat(row.getLockedAt()).isNull();
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(psp.totalInvocations()).isEqualTo(1);

        // Retry — must observe the PSP's cached result via the same derived
        // key, persist tx3 + tx4, return 201, and STILL only one charge.
        IdempotencyOutcome retry = service.execute(
                userId, key, "POST", "/charges", canonical, hash);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(psp.uniqueCharges())
                .as("Critical: retry must not produce a second charge")
                .isEqualTo(1);
        assertThat(psp.totalInvocations())
                .as("Retry should have called the PSP a second time (which dedup'd)")
                .isEqualTo(2);

        IdempotencyKey finished = keyRepo.findByUserIdAndKey(userId, key).orElseThrow();
        assertThat(finished.recoveryPointEnum()).isEqualTo(RecoveryPoint.FINISHED);
        assertThat(rideRepo.findAll()).hasSize(1);
    }

    /**
     * F5/F7-style — simulate "crash after DB commit, before response is
     * returned" by manually rewinding {@code locked_at} past staleness on a
     * row that's at {@code external_api_called}, then re-running.
     */
    @Test
    @Transactional
    void testCrashAfterDbCommitBeforeResponseRecoversCorrectly() {
        String key = UUID.randomUUID().toString();
        String canonical = RequestHash.canonicalize(BODY);
        String hash = RequestHash.sha256OfCanonicalized(BODY);

        // First attempt: run only through tx3 (we drive directly via the
        // phase methods) — the JVM "dies" before runFinalizePhase commits.
        var ar = phases.acquireOrServe(userId, key, "POST", "/charges", canonical, hash);
        long keyId = ((com.yourname.idempotency.service.AcquireResult.Fresh) ar).keyId();
        phases.runStartedPhase(keyId);

        // Compute derived key and call PSP, then commit tx3 — but NOT tx4.
        var snap = phases.snapshot(keyId);
        var result = psp.charge(
                2000L, "usd", "cus_chaos", "idem-" + keyId);
        phases.persistExternalCallResult(keyId, result, "idem-" + keyId);

        // Pretend the process died. Force the lock stale so the retry can
        // reclaim it.
        em.createNativeQuery(
                        "UPDATE idempotency_keys SET locked_at = :past WHERE id = :id")
                .setParameter("past", Instant.now().minusSeconds(120))
                .setParameter("id", keyId)
                .executeUpdate();
        em.flush();
        em.clear();

        // Now a fresh attempt with the same key & body comes in. It should
        // resume from EXTERNAL_API_CALLED and finalize without re-charging.
        IdempotencyOutcome retry = service.execute(
                userId, key, "POST", "/charges", canonical, hash);

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).contains("\"charge_id\":\"" + result.chargeId() + "\"");
        assertThat(psp.uniqueCharges()).isEqualTo(1);

        IdempotencyKey finished = keyRepo.findByUserIdAndKey(userId, key).orElseThrow();
        assertThat(finished.recoveryPointEnum()).isEqualTo(RecoveryPoint.FINISHED);
    }
}
