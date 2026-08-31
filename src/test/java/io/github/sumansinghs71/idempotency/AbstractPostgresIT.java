package io.github.sumansinghs71.idempotency;

import io.github.sumansinghs71.idempotency.model.User;
import io.github.sumansinghs71.idempotency.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for every test that needs a real Postgres.
 *
 * <p><b>Singleton-container pattern, deliberately.</b> There is no
 * {@code @Testcontainers} / {@code @Container} here. Those annotations hand the
 * container lifecycle to JUnit, and for a {@code static} field that means JUnit
 * stops the container when the first test class finishes — every later class
 * then fails to connect. Instead the container is started once from a static
 * initialiser and left running for the whole JVM; the Ryuk sidecar reaps it on
 * exit. See
 * https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/
 *
 * <p><b>Isolation.</b> {@link #truncateAll()} runs before every test and wipes
 * all five tables in one statement. Truncating {@code idempotency_keys} alone
 * is not enough: {@code rides}, {@code audit_logs} and {@code staged_jobs} all
 * reference it {@code ON DELETE SET NULL} (not {@code CASCADE}), so deleting
 * keys leaves orphan rows behind whose key id is now NULL. Those orphans are
 * invisible to per-key lookups but very visible to suite-wide assertions such
 * as {@code rideRepo.count()} — which is exactly what makes a test pass alone
 * and fail in a full run. {@code TRUNCATE ... CASCADE} clears them.
 *
 * <p>Note the deliberate absence of {@code RESTART IDENTITY}. The sequences are
 * left running, because the derived key handed to the PSP is
 * {@code "idem-" + rowId}: resetting ids would make two different tests derive
 * the same PSP key, and one test's charge would silently satisfy another test's
 * call.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("idempotency")
                    .withUsername("idem")
                    .withPassword("idem");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired protected UserRepository userRepo;

    /**
     * Plain JDBC, on purpose. Test setup and out-of-band state fiddling run
     * auto-committed and outside any Spring-managed transaction, so the code
     * under test — whose phases are all {@code REQUIRES_NEW} — sees them
     * immediately, and so no test needs {@code @Transactional} (which would
     * deadlock against those same {@code REQUIRES_NEW} phases).
     */
    @Autowired protected JdbcTemplate jdbc;

    /** Wipes every table before each test. Auto-committed, so it is visible everywhere. */
    @BeforeEach
    public void truncateAll() {
        jdbc.execute(
                "TRUNCATE TABLE staged_jobs, audit_logs, rides, "
                        + "idempotency_keys, users CASCADE");
    }

    protected long seedUser(String email) {
        return userRepo.save(new User(email, "cus_" + email.hashCode())).getId();
    }

    /** Seeds a user with a unique email, for tests that do not care about the address. */
    protected long seedUser() {
        return seedUser("u-" + UUID.randomUUID() + "@example.com");
    }

    // ------------------------------------------------------------------
    // Assertion helpers. All read committed state through plain JDBC, so
    // they observe exactly what a separate process would observe.
    // ------------------------------------------------------------------

    /** Total rides in the database — the primary side-effect count. */
    protected long countRides() {
        return jdbc.queryForObject("SELECT count(*) FROM rides", Long.class);
    }

    /** Total staged jobs — the secondary side effect (receipt sends). */
    protected long countStagedJobs() {
        return jdbc.queryForObject("SELECT count(*) FROM staged_jobs", Long.class);
    }

    /** The idempotency-record row for a key, as raw committed columns. */
    protected Map<String, Object> keyRow(long userId, String key) {
        return jdbc.queryForMap(
                "SELECT * FROM idempotency_keys WHERE user_id = ? AND key = ?", userId, key);
    }

    protected long keyId(long userId, String key) {
        return ((Number) keyRow(userId, key).get("id")).longValue();
    }

    /** The ride attached to a key, as raw committed columns. */
    protected Map<String, Object> rideRow(long keyId) {
        return jdbc.queryForMap("SELECT * FROM rides WHERE idempotency_key_id = ?", keyId);
    }

    /**
     * The audit trail for a key, oldest first, as {@code "action:from->to"}.
     * This is how each test asserts <em>which recovery path</em> was taken —
     * {@code lock_conflict} vs {@code lock_reclaimed} vs {@code cache_hit} are
     * three different mechanisms and the trail distinguishes them.
     */
    protected List<String> auditTrail(long keyId) {
        return jdbc.queryForList(
                        "SELECT action, from_state, to_state FROM audit_logs "
                                + "WHERE idempotency_key_id = ? ORDER BY id ASC",
                        keyId)
                .stream()
                .map(r -> r.get("action") + ":" + r.get("from_state") + "->" + r.get("to_state"))
                .toList();
    }

    protected List<String> auditActions(long keyId) {
        return jdbc.queryForList(
                "SELECT action FROM audit_logs WHERE idempotency_key_id = ? ORDER BY id ASC",
                String.class, keyId);
    }

    /**
     * Backdates {@code locked_at} past the configured staleness window. This is
     * how a test says "the process holding this row died": the row stays locked
     * on disk, and only the passage of time makes it reclaimable.
     */
    protected void expireLock(long keyId) {
        jdbc.update(
                "UPDATE idempotency_keys SET locked_at = now() - interval '1 hour' WHERE id = ?",
                keyId);
    }

    /** Reads a single field out of a JSON response body. */
    protected static String jsonField(String json, String field) {
        try {
            return JSON.readTree(json).path(field).asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("response body was not valid JSON: " + json, e);
        }
    }

    /** Backdates {@code expires_at} so the row is past its TTL. */
    protected void expireKeyTtl(long userId, String key) {
        jdbc.update(
                "UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' "
                        + "WHERE user_id = ? AND key = ?",
                userId, key);
    }
}
