package com.yourname.idempotency;

import com.yourname.idempotency.model.RequestHash;
import com.yourname.idempotency.model.User;
import com.yourname.idempotency.repository.IdempotencyKeyRepository;
import com.yourname.idempotency.repository.UserRepository;
import com.yourname.idempotency.service.FakeExternalPaymentClient;
import com.yourname.idempotency.service.IdempotencyOutcome;
import com.yourname.idempotency.service.IdempotencyService;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * JMH harness — measures throughput and latency of:
 * <ul>
 *   <li>Unique-key requests (full state-machine pass)</li>
 *   <li>Duplicate-key requests (cached path)</li>
 * </ul>
 *
 * <p>Run:
 * <pre>
 *   ./gradlew jmh
 * </pre>
 *
 * <p><b>Do NOT add benchmark numbers to the README until you have actually
 * executed this on your hardware.</b> Hardcoded numbers from someone else's
 * machine are misleading.
 */
@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class IdempotencyBenchmark {

    @Param({"1", "10", "100"})
    public int threads;

    private PostgreSQLContainer<?> pg;
    private ConfigurableApplicationContext ctx;
    private IdempotencyService service;
    private FakeExternalPaymentClient psp;
    private IdempotencyKeyRepository keyRepo;
    private UserRepository userRepo;

    private long userId;
    private String cachedKey;
    private String cachedCanonical;
    private String cachedHash;
    private static final String BODY =
            "{\"amount\":2000,\"currency\":\"usd\",\"customer_id\":\"cus_bench\"}";

    @Setup(Level.Trial)
    public void setUpTrial() {
        pg = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("idempotency")
                .withUsername("idem")
                .withPassword("idem");
        pg.start();

        System.setProperty("spring.datasource.url", pg.getJdbcUrl());
        System.setProperty("spring.datasource.username", pg.getUsername());
        System.setProperty("spring.datasource.password", pg.getPassword());

        ctx = SpringApplication.run(IdempotencyApplication.class, "--server.port=0");
        service = ctx.getBean(IdempotencyService.class);
        psp = ctx.getBean(FakeExternalPaymentClient.class);
        keyRepo = ctx.getBean(IdempotencyKeyRepository.class);
        userRepo = ctx.getBean(UserRepository.class);

        userId = userRepo.save(new User("bench@example.com", "cus_bench")).getId();

        // Prime a row to use for cached-path benchmarks.
        cachedKey = UUID.randomUUID().toString();
        cachedCanonical = RequestHash.canonicalize(BODY);
        cachedHash = RequestHash.sha256OfCanonicalized(BODY);
        service.execute(userId, cachedKey, "POST", "/charges", cachedCanonical, cachedHash);
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (ctx != null) ctx.close();
        if (pg != null) pg.stop();
    }

    @Benchmark
    @Threads(1)
    public IdempotencyOutcome cachedPath() {
        return service.execute(userId, cachedKey, "POST", "/charges",
                cachedCanonical, cachedHash);
    }

    @Benchmark
    @Threads(1)
    public IdempotencyOutcome uniqueKeyPath() {
        String key = UUID.randomUUID().toString();
        return service.execute(userId, key, "POST", "/charges",
                cachedCanonical, cachedHash);
    }
}
