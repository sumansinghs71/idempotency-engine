package io.github.sumansinghs71.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sumansinghs71.idempotency.AbstractPostgresIT;
import io.github.sumansinghs71.idempotency.model.RequestHash;
import io.github.sumansinghs71.idempotency.service.FakeExternalPaymentClient;
import io.github.sumansinghs71.idempotency.service.IdempotencyOutcome;
import io.github.sumansinghs71.idempotency.service.IdempotencyService;
import io.github.sumansinghs71.idempotency.service.JobDrain;
import io.github.sumansinghs71.idempotency.service.Reaper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The parts of the contract that are not failure recovery: the happy path, the
 * body-fingerprint check, TTL expiry, per-user key scoping, card declines, and
 * the two background sweepers.
 *
 * <p>No {@code @Transactional} anywhere in this class, deliberately. The code
 * under test runs every phase in {@code Propagation.REQUIRES_NEW}. A
 * test-managed transaction wrapping it would hold the {@code users} row it had
 * just inserted while the phase transaction — a genuinely separate database
 * transaction — tried to reference a row it cannot see, producing foreign-key
 * violations. State is set up and inspected through committed JDBC instead, and
 * {@link AbstractPostgresIT#truncateAll()} cleans up between tests.
 */
class IdempotencyIntegrationTest extends AbstractPostgresIT {

    @Autowired IdempotencyService service;
    @Autowired FakeExternalPaymentClient psp;
    @Autowired JobDrain jobDrain;
    @Autowired Reaper reaper;

    private static final String OK_BODY =
            "{\"amount\":2000,\"currency\":\"usd\",\"customer_id\":\"cus_test\"}";

    private long userId;

    @BeforeEach
    void setUp() {
        // Full reset: counters, injected failures, and the PSP's own dedup store.
        psp.reset();
        userId = seedUser("alice-" + UUID.randomUUID() + "@example.com");
    }

    private IdempotencyOutcome execute(long user, String key, String body) {
        return service.execute(
                user, key, "POST", "/charges",
                RequestHash.canonicalize(body),
                RequestHash.sha256OfCanonicalized(body));
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("Happy path: one request → 201, one ride, one charge, one staged job")
    void happyPathChargesOnce() {
        String key = UUID.randomUUID().toString();

        IdempotencyOutcome out = execute(userId, key, OK_BODY);

        assertThat(out.statusCode()).isEqualTo(201);
        assertThat(jsonField(out.body(), "status")).isEqualTo("succeeded");
        assertThat(jsonField(out.body(), "amount")).isEqualTo("2000");
        assertThat(psp.totalInvocations()).isEqualTo(1);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isEqualTo(1);

        long id = keyId(userId, key);
        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("finished");
        assertThat(row.get("response_code")).isEqualTo(201);
        assertThat(row.get("locked_at")).isNull();
        assertThat(rideRow(id).get("status")).isEqualTo("charged");
        assertThat(auditActions(id)).containsExactly(
                "key_created",
                "phase_committed",   // started            -> customer_validated
                "phase_committed",   // customer_validated -> external_api_called
                "phase_committed");  // external_api_called-> finished
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("Same key + same body → the cached response, no second charge")
    void duplicateKeyReturnsCachedResponse() {
        String key = UUID.randomUUID().toString();

        IdempotencyOutcome first = execute(userId, key, OK_BODY);
        IdempotencyOutcome second = execute(userId, key, OK_BODY);

        assertThat(second.statusCode()).isEqualTo(first.statusCode());
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(psp.totalInvocations())
                .as("a cached replay must not call the PSP again")
                .isEqualTo(1);
        assertThat(countRides()).isEqualTo(1);
        assertThat(auditActions(keyId(userId, key))).contains("cache_hit");
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("Same key + different body → 422, and the original stays intact")
    void duplicateKeyDifferentBodyRejects() {
        String key = UUID.randomUUID().toString();
        IdempotencyOutcome first = execute(userId, key, OK_BODY);

        String otherBody = "{\"amount\":9999,\"currency\":\"usd\",\"customer_id\":\"cus_test\"}";
        IdempotencyOutcome out = execute(userId, key, otherBody);

        assertThat(out.statusCode()).isEqualTo(422);
        assertThat(out.body()).contains("idempotency_key_body_mismatch");
        assertThat(psp.totalInvocations()).as("the 422 path must not call the PSP").isEqualTo(1);
        assertThat(countRides()).isEqualTo(1);

        long id = keyId(userId, key);
        assertThat(keyRow(userId, key).get("recovery_point")).isEqualTo("finished");
        assertThat(auditActions(id)).contains("body_mismatch");

        // The original response is still served for the original body.
        assertThat(execute(userId, key, OK_BODY).body()).isEqualTo(first.body());
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("Key reuse is rejected only within the TTL; past it the key is free again")
    void expiredKeyAllowsNewRequest() {
        String key = UUID.randomUUID().toString();
        execute(userId, key, OK_BODY);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        long firstKeyId = keyId(userId, key);

        expireKeyTtl(userId, key);

        IdempotencyOutcome out = execute(userId, key, OK_BODY);

        // Past the TTL the key row is treated as if it never existed: a new row
        // with a new id, therefore a new derived PSP key, therefore a genuinely
        // new charge. That is intended — the TTL is the contract's time bound,
        // and it is why a client must not reuse a key across days.
        assertThat(out.statusCode()).isEqualTo(201);
        assertThat(psp.uniqueCharges()).isEqualTo(2);
        assertThat(countRides()).isEqualTo(2);

        long secondKeyId = keyId(userId, key);
        assertThat(secondKeyId)
                .as("the expired row was replaced, not resurrected")
                .isNotEqualTo(firstKeyId);
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("The same key string from two users does not collide")
    void keyCollisionAcrossUsersAllowed() {
        long bob = seedUser("bob-" + UUID.randomUUID() + "@example.com");
        String key = "shared-key-value";

        IdempotencyOutcome a = execute(userId, key, OK_BODY);
        IdempotencyOutcome b = execute(bob, key, OK_BODY);

        assertThat(a.statusCode()).isEqualTo(201);
        assertThat(b.statusCode()).isEqualTo(201);
        assertThat(psp.uniqueCharges()).isEqualTo(2);
        assertThat(countRides()).isEqualTo(2);

        assertThat(keyId(userId, key)).isNotEqualTo(keyId(bob, key));
        assertThat(keyRow(userId, key).get("recovery_point")).isEqualTo("finished");
        assertThat(keyRow(bob, key).get("recovery_point")).isEqualTo("finished");
        assertThat(a.body()).isNotEqualTo(b.body());
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("A card decline is cached like any other outcome: 402, once")
    void declinedChargeIsCachedAndNotRetriedAtThePsp() {
        psp.forceDecline("insufficient_funds");
        String key = UUID.randomUUID().toString();

        IdempotencyOutcome first = execute(userId, key, OK_BODY);
        IdempotencyOutcome second = execute(userId, key, OK_BODY);

        assertThat(first.statusCode()).isEqualTo(402);
        assertThat(jsonField(first.body(), "decline_code")).isEqualTo("insufficient_funds");
        assertThat(second.statusCode()).isEqualTo(402);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(psp.totalInvocations()).isEqualTo(1);
        assertThat(psp.uniqueCharges()).as("a decline is not a charge").isZero();

        long id = keyId(userId, key);
        assertThat(keyRow(userId, key).get("recovery_point")).isEqualTo("finished");
        assertThat(keyRow(userId, key).get("response_code")).isEqualTo(402);
        assertThat(rideRow(id).get("status")).isEqualTo("declined");
        assertThat(countStagedJobs()).as("a declined charge stages no receipt").isZero();
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("JobDrain deletes the staged receipt, and a second tick is a no-op")
    void jobDrainProcessesStagedJob() {
        execute(userId, UUID.randomUUID().toString(), OK_BODY);
        assertThat(countStagedJobs()).isEqualTo(1);

        jobDrain.drain();
        assertThat(countStagedJobs()).isZero();

        jobDrain.drain();
        assertThat(countStagedJobs()).isZero();
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("Reaper deletes expired keys and leaves the business rows behind")
    void reaperDeletesExpiredKeysButKeepsRides() {
        String key = UUID.randomUUID().toString();
        execute(userId, key, OK_BODY);
        long id = keyId(userId, key);
        expireKeyTtl(userId, key);

        reaper.reap();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_keys WHERE id = ?", Long.class, id))
                .isZero();
        // ON DELETE SET NULL, not CASCADE: the ride and its audit trail survive
        // the reaper with a NULL key id. Money moved; the record of it stays.
        assertThat(countRides()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM rides WHERE idempotency_key_id IS NULL", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Long.class))
                .isGreaterThan(0L);
    }
}
