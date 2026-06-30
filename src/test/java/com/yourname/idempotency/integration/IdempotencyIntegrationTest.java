package com.yourname.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yourname.idempotency.AbstractPostgresIT;
import com.yourname.idempotency.model.IdempotencyKey;
import com.yourname.idempotency.model.RecoveryPoint;
import com.yourname.idempotency.model.RequestHash;
import com.yourname.idempotency.repository.IdempotencyKeyRepository;
import com.yourname.idempotency.service.FakeExternalPaymentClient;
import com.yourname.idempotency.service.IdempotencyOutcome;
import com.yourname.idempotency.service.IdempotencyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 8 — Tests 1, 2, 3, 7, 8.
 *
 * <p>Tests 4 (concurrency), 5 and 6 (chaos) live in separate classes.
 */
class IdempotencyIntegrationTest extends AbstractPostgresIT {

    @Autowired IdempotencyService service;
    @Autowired FakeExternalPaymentClient psp;
    @Autowired IdempotencyKeyRepository keyRepo;
    @PersistenceContext EntityManager em;

    private long userId;

    @BeforeEach
    void setUp() {
        psp.resetCounters();
        psp.clearForcedDecline();
        // Truncate idempotency-related tables but keep users table semantics
        // simple by seeding a fresh user per test.
        keyRepo.deleteAll();
        userId = seedUser("alice-" + UUID.randomUUID() + "@example.com");
    }

    private static final String OK_BODY = "{\"amount\":2000,\"currency\":\"usd\",\"customer_id\":\"cus_test\"}";

    private static String canonical(String json) {
        return RequestHash.canonicalize(json);
    }
    private static String hash(String json) {
        return RequestHash.sha256OfCanonicalized(json);
    }

    // -------------------------------------------------------------------
    // FR-1 / FR-2 / FR-5 happy path
    // -------------------------------------------------------------------
    @Test
    void testHappyPath() {
        String key = UUID.randomUUID().toString();
        IdempotencyOutcome out = service.execute(
                userId, key, "POST", "/charges", canonical(OK_BODY), hash(OK_BODY));

        assertThat(out.statusCode()).isEqualTo(201);
        assertThat(out.body()).contains("\"status\":\"succeeded\"");
        assertThat(psp.totalInvocations()).isEqualTo(1);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
    }

    // -------------------------------------------------------------------
    // FR-2 — second request with same key + same body → cached response
    // -------------------------------------------------------------------
    @Test
    void testDuplicateKeyReturnsCachedResponse() {
        String key = UUID.randomUUID().toString();
        IdempotencyOutcome first = service.execute(
                userId, key, "POST", "/charges", canonical(OK_BODY), hash(OK_BODY));

        IdempotencyOutcome second = service.execute(
                userId, key, "POST", "/charges", canonical(OK_BODY), hash(OK_BODY));

        assertThat(second.statusCode()).isEqualTo(first.statusCode());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(psp.totalInvocations())
                .as("Cached replay must not call the PSP again")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------
    // FR-3 — same key, different body → 422
    // -------------------------------------------------------------------
    @Test
    void testDuplicateKeyDifferentBodyRejects() {
        String key = UUID.randomUUID().toString();
        service.execute(userId, key, "POST", "/charges", canonical(OK_BODY), hash(OK_BODY));

        String otherBody = "{\"amount\":9999,\"currency\":\"usd\",\"customer_id\":\"cus_test\"}";
        IdempotencyOutcome out = service.execute(
                userId, key, "POST", "/charges", canonical(otherBody), hash(otherBody));

        assertThat(out.statusCode()).isEqualTo(422);
        assertThat(out.body()).contains("idempotency_key_body_mismatch");
        assertThat(psp.totalInvocations())
                .as("422 path must not call the PSP")
                .isEqualTo(1);
    }

    // -------------------------------------------------------------------
    // FR-6 — expired key allows a fresh request
    // -------------------------------------------------------------------
    @Test
    @Transactional
    void testExpiredKeyAllowsNewRequest() {
        String key = UUID.randomUUID().toString();
        service.execute(userId, key, "POST", "/charges", canonical(OK_BODY), hash(OK_BODY));
        assertThat(psp.totalInvocations()).isEqualTo(1);

        // Force-expire the row in the DB.
        em.createNativeQuery(
                        "UPDATE idempotency_keys SET expires_at = :past WHERE user_id = :u AND key = :k")
                .setParameter("past", Instant.now().minusSeconds(60))
                .setParameter("u", userId)
                .setParameter("k", key)
                .executeUpdate();
        em.flush();
        em.clear();

        IdempotencyOutcome out = service.execute(
                userId, key, "POST", "/charges", canonical(OK_BODY), hash(OK_BODY));
        assertThat(out.statusCode()).isEqualTo(201);
        // Two PSP charges this time because the expired row was treated as new.
        assertThat(psp.uniqueCharges()).isEqualTo(2);
    }

    // -------------------------------------------------------------------
    // FR-7 — two users with the same key string do NOT collide
    // -------------------------------------------------------------------
    @Test
    void testKeyCollisionAcrossUsersAllowed() {
        long bob = seedUser("bob-" + UUID.randomUUID() + "@example.com");
        String key = "shared-key-value";

        IdempotencyOutcome a = service.execute(
                userId, key, "POST", "/charges", canonical(OK_BODY), hash(OK_BODY));
        IdempotencyOutcome b = service.execute(
                bob, key, "POST", "/charges", canonical(OK_BODY), hash(OK_BODY));

        assertThat(a.statusCode()).isEqualTo(201);
        assertThat(b.statusCode()).isEqualTo(201);
        // Different rides → different PSP charges
        assertThat(psp.uniqueCharges()).isEqualTo(2);

        IdempotencyKey aliceRow = keyRepo.findByUserIdAndKey(userId, key).orElseThrow();
        IdempotencyKey bobRow = keyRepo.findByUserIdAndKey(bob, key).orElseThrow();
        assertThat(aliceRow.getId()).isNotEqualTo(bobRow.getId());
        assertThat(aliceRow.recoveryPointEnum()).isEqualTo(RecoveryPoint.FINISHED);
        assertThat(bobRow.recoveryPointEnum()).isEqualTo(RecoveryPoint.FINISHED);
    }
}
