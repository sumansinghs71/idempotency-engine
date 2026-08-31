package io.github.sumansinghs71.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sumansinghs71.idempotency.AbstractPostgresIT;
import io.github.sumansinghs71.idempotency.model.RequestHash;
import io.github.sumansinghs71.idempotency.service.FakeExternalPaymentClient;
import io.github.sumansinghs71.idempotency.service.IdempotencyOutcome;
import io.github.sumansinghs71.idempotency.service.IdempotencyService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Concurrency: many simultaneous copies of one logical request.
 *
 * <p>The interesting part is not that duplicates are rejected — it is that the
 * rejection is a <em>409, not silence</em>. A losing thread is told to retry,
 * and when it does it gets the winner's response. Asserting only "one charge"
 * would also pass if every thread had silently done nothing.
 */
class ConcurrencyTest extends AbstractPostgresIT {

    @Autowired IdempotencyService service;
    @Autowired FakeExternalPaymentClient psp;

    private long userId;

    @BeforeEach
    void setUp() {
        // Full reset: counters, injected failures, and the PSP's own dedup store.
        psp.reset();
        userId = seedUser("conc-" + UUID.randomUUID() + "@example.com");
    }

    @Test
    @DisplayName("100 concurrent copies of one request → exactly one ride and one charge")
    void concurrentRequestsExecuteOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"amount\":2000,\"currency\":\"usd\",\"customer_id\":\"cus_x\"}";
        String canonical = RequestHash.canonicalize(body);
        String hash = RequestHash.sha256OfCanonicalized(body);

        int concurrency = 100;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        int ok;
        int conflict;
        try {
            List<Future<IdempotencyOutcome>> futures = pool.invokeAll(
                    Collections.nCopies(
                            concurrency,
                            (Callable<IdempotencyOutcome>) () -> service.execute(
                                    userId, key, "POST", "/charges", canonical, hash)),
                    60, TimeUnit.SECONDS);

            ok = 0;
            conflict = 0;
            for (Future<IdempotencyOutcome> f : futures) {
                IdempotencyOutcome o = f.get();
                if (o.statusCode() == 201) {
                    ok++;
                } else if (o.statusCode() == 409) {
                    conflict++;
                    assertThat(o.retryAfterMs())
                            .as("a 409 must tell the client when to come back")
                            .isNotNull()
                            .isPositive();
                } else {
                    throw new AssertionError("Unexpected status: " + o.statusCode());
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // Every thread got a definite answer; none errored out.
        assertThat(ok).isGreaterThanOrEqualTo(1);
        assertThat(ok + conflict).isEqualTo(concurrency);

        // 1. Side-effect count: one, regardless of how the 100 threads raced.
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isEqualTo(1);
        assertThat(psp.uniqueCharges())
                .as("exactly one PSP charge across %d concurrent attempts", concurrency)
                .isEqualTo(1);

        // 2. Idempotency record: exactly one row, finished and unlocked.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND key = ?",
                        Long.class, userId, key))
                .isEqualTo(1);
        long id = keyId(userId, key);
        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("finished");
        assertThat(row.get("response_code")).isEqualTo(201);
        assertThat(row.get("locked_at")).isNull();

        // 3. Final charge state.
        Map<String, Object> ride = rideRow(id);
        assertThat(ride.get("status")).isEqualTo("charged");
        assertThat(ride.get("psp_charge_id")).isNotNull();

        // 4. Recovery path: the losers were turned away by the live lock, and
        //    the winner ran each phase exactly once.
        List<String> actions = auditActions(id);
        assertThat(actions).startsWith("key_created");
        assertThat(actions.stream().filter("phase_committed"::equals).count())
                .as("each of the three DAG transitions committed exactly once, "
                        + "no matter how many threads raced")
                .isEqualTo(3);
        if (conflict > 0) {
            assertThat(actions).contains("lock_conflict");
        }

        // A loser retrying after its backoff gets the winner's response.
        IdempotencyOutcome retry = service.execute(
                userId, key, "POST", "/charges", canonical, hash);
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).contains((String) ride.get("psp_charge_id"));
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(countRides()).isEqualTo(1);
    }
}
