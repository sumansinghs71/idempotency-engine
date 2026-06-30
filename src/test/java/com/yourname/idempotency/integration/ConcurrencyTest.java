package com.yourname.idempotency.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.yourname.idempotency.AbstractPostgresIT;
import com.yourname.idempotency.model.RequestHash;
import com.yourname.idempotency.repository.IdempotencyKeyRepository;
import com.yourname.idempotency.repository.RideRepository;
import com.yourname.idempotency.service.FakeExternalPaymentClient;
import com.yourname.idempotency.service.IdempotencyOutcome;
import com.yourname.idempotency.service.IdempotencyService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConcurrencyTest extends AbstractPostgresIT {

    @Autowired IdempotencyService service;
    @Autowired FakeExternalPaymentClient psp;
    @Autowired IdempotencyKeyRepository keyRepo;
    @Autowired RideRepository rideRepo;

    private long userId;

    @BeforeEach
    void setUp() {
        psp.resetCounters();
        psp.clearForcedDecline();
        keyRepo.deleteAll();
        userId = seedUser("conc-" + UUID.randomUUID() + "@example.com");
    }

    /**
     * FR-4: 100 concurrent threads sending the same (user_id, key) must result
     * in exactly 1 ride row and exactly 1 PSP charge. Threads that lose the
     * race get 409 first, then eventually retry and observe the cached 201.
     */
    @Test
    void testConcurrentRequestsExecuteOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"amount\":2000,\"currency\":\"usd\",\"customer_id\":\"cus_x\"}";
        String canonical = RequestHash.canonicalize(body);
        String hash = RequestHash.sha256OfCanonicalized(body);

        int concurrency = 100;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Future<IdempotencyOutcome>> futures = pool.invokeAll(
                    java.util.Collections.nCopies(concurrency,
                            (Callable<IdempotencyOutcome>) () ->
                                    service.execute(userId, key, "POST", "/charges",
                                            canonical, hash)),
                    60, TimeUnit.SECONDS);

            int ok = 0, conflict = 0;
            for (Future<IdempotencyOutcome> f : futures) {
                IdempotencyOutcome o = f.get();
                if (o.statusCode() == 201) ok++;
                else if (o.statusCode() == 409) conflict++;
                else throw new AssertionError("Unexpected status: " + o.statusCode());
            }

            // At least one thread executed and returned 201; the rest either
            // got 201 from the cache or 409 (and would normally retry).
            assertThat(ok).isGreaterThanOrEqualTo(1);
            assertThat(ok + conflict).isEqualTo(concurrency);
        } finally {
            pool.shutdownNow();
        }

        // Drive losers to retry; they should observe 201 from the cache.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            IdempotencyOutcome retry = service.execute(
                    userId, key, "POST", "/charges", canonical, hash);
            assertThat(retry.statusCode()).isEqualTo(201);
        });

        // The critical assertions: exactly one ride, exactly one PSP charge.
        assertThat(rideRepo.findAll()).hasSize(1);
        assertThat(psp.uniqueCharges())
                .as("Exactly one PSP charge across 100 concurrent attempts")
                .isEqualTo(1);
    }
}
