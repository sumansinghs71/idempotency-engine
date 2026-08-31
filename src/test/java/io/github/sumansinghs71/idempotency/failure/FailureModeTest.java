package io.github.sumansinghs71.idempotency.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sumansinghs71.idempotency.AbstractPostgresIT;
import io.github.sumansinghs71.idempotency.IdempotencyApplication;
import io.github.sumansinghs71.idempotency.model.RequestHash;
import io.github.sumansinghs71.idempotency.service.AcquireResult;
import io.github.sumansinghs71.idempotency.service.FakeExternalPaymentClient;
import io.github.sumansinghs71.idempotency.service.IdempotencyOutcome;
import io.github.sumansinghs71.idempotency.service.IdempotencyService;
import io.github.sumansinghs71.idempotency.service.PhaseTransactions;
import io.github.sumansinghs71.idempotency.service.PspChargeResult;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The eight enumerated failure points, one test each (F7 has two, for the two
 * sides of the ambiguity).
 *
 * <p>Every test asserts the same four things, because any one of them alone can
 * hide a duplicate charge:
 *
 * <ol>
 *   <li><b>Side-effect count</b> — rides, staged jobs, and unique PSP charges.</li>
 *   <li><b>Idempotency-record state</b> — {@code recovery_point},
 *       {@code response_code}, {@code locked_at}, {@code attempt_no}.</li>
 *   <li><b>Final charge state</b> — the ride's {@code status} and
 *       {@code psp_charge_id}.</li>
 *   <li><b>Recovery path taken</b> — the {@code audit_logs} trail, which
 *       distinguishes {@code lock_conflict} from {@code lock_reclaimed} from
 *       {@code cache_hit}. Without this a test can pass for the wrong reason:
 *       "one charge" is also true if the second request silently did nothing.</li>
 * </ol>
 *
 * <p>The failures are injected in-process rather than at the network layer: PSP
 * exceptions come from {@link FakeExternalPaymentClient}'s hooks, a mid-phase
 * DB commit failure comes from a temporary Postgres trigger, and a process
 * death is modelled by stopping the state machine between phases and backdating
 * {@code locked_at} — which is exactly the state a dead JVM leaves on disk.
 */
class FailureModeTest extends AbstractPostgresIT {

    @Autowired IdempotencyService service;
    @Autowired PhaseTransactions phases;
    @Autowired FakeExternalPaymentClient psp;

    private static final String BODY =
            "{\"amount\":2000,\"currency\":\"usd\",\"customer_id\":\"cus_failure\"}";
    private static final String CANONICAL = RequestHash.canonicalize(BODY);
    private static final String HASH = RequestHash.sha256OfCanonicalized(BODY);

    private long userId;
    private String key;

    @BeforeEach
    void setUp() {
        // Full reset: counters, injected failures, and the PSP's own dedup store.
        psp.reset();
        userId = seedUser();
        key = UUID.randomUUID().toString();
    }

    private IdempotencyOutcome execute() {
        return service.execute(userId, key, "POST", "/charges", CANONICAL, HASH);
    }

    // =====================================================================
    // F1 — a duplicate is delivered before the original has done any work.
    // =====================================================================
    @Test
    @DisplayName("F1: duplicate delivered before processing begins → 409, no side effects")
    void f1_duplicateBeforeProcessing() {
        // The original request has claimed the row and nothing else. This is
        // the state a client's over-eager retry (or a load balancer replaying
        // the request) sees when it arrives microseconds behind the original.
        AcquireResult first = phases.acquireOrServe(
                userId, key, "POST", "/charges", CANONICAL, HASH);
        long id = ((AcquireResult.Fresh) first).keyId();

        IdempotencyOutcome duplicate = execute();

        // 1. Side effects: none at all. Nothing has run yet, so nothing may.
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(countRides()).isZero();
        assertThat(countStagedJobs()).isZero();
        assertThat(psp.totalInvocations()).isZero();
        assertThat(psp.uniqueCharges()).isZero();

        // 2. Idempotency record: still at the entry state, still held.
        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("started");
        assertThat(row.get("response_code")).isNull();
        assertThat(row.get("locked_at")).as("original still holds the lock").isNotNull();
        assertThat(row.get("attempt_no")).isEqualTo(1);

        // 3. Final charge state: no ride exists to have a charge state.
        assertThat(countRides()).isZero();

        // 4. Recovery path: rejected by the live lock, not by a cache hit.
        assertThat(auditTrail(id)).containsExactly(
                "key_created:null->started",
                "lock_conflict:started->null");

        // And the request still converges once the original's lock goes stale.
        expireLock(id);
        IdempotencyOutcome resumed = execute();
        assertThat(resumed.statusCode()).isEqualTo(201);
        assertThat(countRides()).isEqualTo(1);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
    }

    // =====================================================================
    // F2 — a duplicate is delivered while the original is mid-flight, past
    //      the point where it has already created the business record.
    // =====================================================================
    @Test
    @DisplayName("F2: duplicate while in flight → 409, the pending ride is not duplicated")
    void f2_duplicateWhileInFlight() {
        AcquireResult first = phases.acquireOrServe(
                userId, key, "POST", "/charges", CANONICAL, HASH);
        long id = ((AcquireResult.Fresh) first).keyId();
        phases.runStartedPhase(id); // tx2 committed: a pending ride now exists.

        IdempotencyOutcome duplicate = execute();

        // 1. Side effects: exactly the one pending ride the original created.
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(countRides()).as("the duplicate must not insert a second ride").isEqualTo(1);
        assertThat(countStagedJobs()).isZero();
        assertThat(psp.totalInvocations())
                .as("the duplicate must not reach the PSP while the original holds the lock")
                .isZero();

        // 2. Idempotency record: mid-DAG, still locked by the original.
        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("customer_validated");
        assertThat(row.get("response_code")).isNull();
        assertThat(row.get("locked_at")).isNotNull();
        assertThat(row.get("attempt_no")).isEqualTo(1);

        // 3. Final charge state: ride still pending, never charged.
        Map<String, Object> ride = rideRow(id);
        assertThat(ride.get("status")).isEqualTo("pending");
        assertThat(ride.get("psp_charge_id")).isNull();

        // 4. Recovery path: live-lock conflict raised from customer_validated.
        assertThat(auditTrail(id)).containsExactly(
                "key_created:null->started",
                "phase_committed:started->customer_validated",
                "lock_conflict:customer_validated->null");

        // Converges after the holder is presumed dead.
        expireLock(id);
        assertThat(execute().statusCode()).isEqualTo(201);
        assertThat(countRides()).isEqualTo(1);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
    }

    // =====================================================================
    // F3 — the PSP charged, then OUR database commit failed.
    //      The money moved and we have no record of it.
    // =====================================================================
    @Test
    @DisplayName("F3: PSP success then DB commit failure → retry converges on one charge")
    void f3_pspSuccessThenDbCommitFailure() {
        // Break the tx3 write for real: a trigger that aborts any UPDATE on
        // rides. The PSP call in front of it still succeeds, so this reproduces
        // "money moved, our transaction rolled back" rather than simulating it.
        jdbc.execute(
                "CREATE OR REPLACE FUNCTION test_abort_ride_update() RETURNS trigger AS $$ "
                        + "BEGIN RAISE EXCEPTION 'injected tx3 commit failure'; END; "
                        + "$$ LANGUAGE plpgsql");
        jdbc.execute(
                "CREATE TRIGGER trg_test_abort_ride_update BEFORE UPDATE ON rides "
                        + "FOR EACH ROW EXECUTE FUNCTION test_abort_ride_update()");
        try {
            assertThatThrownBy(this::execute)
                    .hasStackTraceContaining("injected tx3 commit failure");
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_abort_ride_update ON rides");
        }

        long id = keyId(userId, key);

        // 1. Side effects after the failure: the PSP charged once; our DB has
        //    the pending ride from tx2 and nothing from tx3.
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isZero();

        // 2. Idempotency record: tx3 rolled back, so the recovery point never
        //    advanced; the lock was released so a retry can pick it straight up.
        Map<String, Object> afterFailure = keyRow(userId, key);
        assertThat(afterFailure.get("recovery_point")).isEqualTo("customer_validated");
        assertThat(afterFailure.get("response_code")).isNull();
        assertThat(afterFailure.get("locked_at"))
                .as("releaseLockOnError must unlock so the retry is not stuck for 90s")
                .isNull();

        // 3. Final charge state at this point: unrecorded. This is the danger
        //    window the derived key exists to close.
        assertThat(rideRow(id).get("psp_charge_id")).isNull();
        assertThat(rideRow(id).get("status")).isEqualTo("pending");

        // Retry with the trigger gone.
        IdempotencyOutcome retry = execute();

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(psp.uniqueCharges())
                .as("the customer must not be charged a second time")
                .isEqualTo(1);
        assertThat(psp.totalInvocations())
                .as("the retry did call the PSP again — the PSP deduplicated it")
                .isEqualTo(2);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isEqualTo(1);

        Map<String, Object> finished = keyRow(userId, key);
        assertThat(finished.get("recovery_point")).isEqualTo("finished");
        assertThat(finished.get("response_code")).isEqualTo(201);
        assertThat(finished.get("locked_at")).isNull();
        assertThat(finished.get("attempt_no")).isEqualTo(2);

        Map<String, Object> ride = rideRow(id);
        assertThat(ride.get("status")).isEqualTo("charged");
        assertThat(ride.get("psp_charge_id")).isNotNull();

        // 4. Recovery path: the unlocked row was reclaimed and replayed from
        //    customer_validated — it did not restart from scratch.
        assertThat(auditActions(id)).containsExactly(
                "key_created", "phase_committed", "lock_reclaimed",
                "phase_committed", "phase_committed");
        assertThat(auditTrail(id)).contains("lock_reclaimed:customer_validated->customer_validated");
    }

    // =====================================================================
    // F4 — everything committed; the response never reached the client.
    // =====================================================================
    @Test
    @DisplayName("F4: DB success then response lost → replay returns the byte-identical body")
    void f4_dbSuccessThenResponseLost() {
        IdempotencyOutcome original = execute();
        assertThat(original.statusCode()).isEqualTo(201);
        long id = keyId(userId, key);

        // The client saw a socket timeout and has no idea this happened. It
        // retries the identical request.
        IdempotencyOutcome replay = execute();

        // 1. Side effects: unchanged by the replay.
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isEqualTo(1);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(psp.totalInvocations())
                .as("a cached replay must not touch the PSP at all")
                .isEqualTo(1);

        // 2. Idempotency record: terminal, unlocked, still on attempt 1 — the
        //    replay served from cache without re-acquiring.
        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("finished");
        assertThat(row.get("response_code")).isEqualTo(201);
        assertThat(row.get("locked_at")).isNull();
        assertThat(row.get("attempt_no")).isEqualTo(1);

        // 3. Final charge state.
        Map<String, Object> ride = rideRow(id);
        assertThat(ride.get("status")).isEqualTo("charged");
        assertThat(ride.get("psp_charge_id")).isNotNull();

        // The contract the client depends on: same status, same bytes.
        assertThat(replay.statusCode()).isEqualTo(original.statusCode());
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(replay.body()).contains((String) ride.get("psp_charge_id"));

        // 4. Recovery path: served from the cached response, not re-executed.
        assertThat(auditActions(id)).containsExactly(
                "key_created",
                "phase_committed",   // started            -> customer_validated
                "phase_committed",   // customer_validated -> external_api_called
                "phase_committed",   // external_api_called-> finished
                "cache_hit");
    }

    // =====================================================================
    // F5 — the process dies after tx2, before the PSP was ever called.
    // =====================================================================
    @Test
    @DisplayName("F5: crash before the PSP call → resume charges exactly once")
    void f5_crashBeforePsp() {
        AcquireResult first = phases.acquireOrServe(
                userId, key, "POST", "/charges", CANONICAL, HASH);
        long id = ((AcquireResult.Fresh) first).keyId();
        phases.runStartedPhase(id);

        // The JVM dies here. On disk: a pending ride, recovery_point =
        // customer_validated, and a locked_at that nobody will ever release.
        assertThat(psp.totalInvocations()).as("the PSP was never reached").isZero();
        assertThat(keyRow(userId, key).get("locked_at")).isNotNull();

        expireLock(id); // time passes; the holder is presumed dead

        IdempotencyOutcome resumed = execute();

        // 1. Side effects: exactly one of each, despite the interrupted attempt.
        assertThat(resumed.statusCode()).isEqualTo(201);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isEqualTo(1);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(psp.totalInvocations())
                .as("the PSP is called once and only once — the crash was before it")
                .isEqualTo(1);

        // 2. Idempotency record: finished on the second attempt.
        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("finished");
        assertThat(row.get("response_code")).isEqualTo(201);
        assertThat(row.get("locked_at")).isNull();
        assertThat(row.get("attempt_no")).isEqualTo(2);

        // 3. Final charge state.
        Map<String, Object> ride = rideRow(id);
        assertThat(ride.get("status")).isEqualTo("charged");
        assertThat(ride.get("psp_charge_id")).isNotNull();

        // 4. Recovery path: stale lock reclaimed, resumed from customer_validated,
        //    and the started phase was NOT re-run.
        assertThat(auditActions(id)).containsExactly(
                "key_created", "phase_committed", "lock_reclaimed",
                "phase_committed", "phase_committed");
        assertThat(auditTrail(id)).contains("lock_reclaimed:customer_validated->customer_validated");
    }

    // =====================================================================
    // F6 — the process dies after the PSP returned success, before tx3.
    //      The outcome was known to the dead process and lost with it.
    // =====================================================================
    @Test
    @DisplayName("F6: crash after PSP success → retry recovers the same charge id")
    void f6_crashAfterPsp() {
        AcquireResult first = phases.acquireOrServe(
                userId, key, "POST", "/charges", CANONICAL, HASH);
        long id = ((AcquireResult.Fresh) first).keyId();
        phases.runStartedPhase(id);

        // The orchestrator calls the PSP outside any transaction and gets a
        // success back — then the JVM dies before persistExternalCallResult.
        // The derived key is the row id, so it is reconstructible after death.
        PspChargeResult charged = psp.charge(2000L, "usd", "cus_failure", "idem-" + id);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(rideRow(id).get("psp_charge_id"))
                .as("the charge id died with the process — nothing persisted it")
                .isNull();

        expireLock(id);

        IdempotencyOutcome resumed = execute();

        // 1. Side effects: still exactly one charge.
        assertThat(resumed.statusCode()).isEqualTo(201);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isEqualTo(1);
        assertThat(psp.uniqueCharges())
                .as("the retry must reuse the pre-crash charge, not create a new one")
                .isEqualTo(1);
        assertThat(psp.totalInvocations()).isEqualTo(2);

        // 2. Idempotency record.
        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("finished");
        assertThat(row.get("response_code")).isEqualTo(201);
        assertThat(row.get("locked_at")).isNull();
        assertThat(row.get("attempt_no")).isEqualTo(2);

        // 3. Final charge state: the SAME charge id the dead process obtained.
        Map<String, Object> ride = rideRow(id);
        assertThat(ride.get("status")).isEqualTo("charged");
        assertThat(ride.get("psp_charge_id")).isEqualTo(charged.chargeId());
        assertThat(resumed.body()).contains(charged.chargeId());

        // 4. Recovery path: reclaimed and resumed from customer_validated.
        assertThat(auditActions(id)).containsExactly(
                "key_created", "phase_committed", "lock_reclaimed",
                "phase_committed", "phase_committed");
    }

    // =====================================================================
    // F7 — the PSP call times out. We cannot tell whether it charged.
    //      Both sides of the ambiguity must converge on one charge.
    // =====================================================================
    @Test
    @DisplayName("F7a: ambiguous timeout, PSP DID charge → retry does not double-charge")
    void f7a_ambiguousTimeout_pspDidCharge() {
        psp.failNextCallAfterCharging();

        assertThatThrownBy(this::execute)
                .isInstanceOf(FakeExternalPaymentClient.TransientPspException.class);

        long id = keyId(userId, key);

        // 1. Side effects after the timeout: the card WAS charged, but the
        //    caller has no way to know that.
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isZero();

        // 2. Idempotency record: no recovery point was committed for an
        //    outcome we never observed. That is the point — committing
        //    external_api_called here would be a lie.
        Map<String, Object> afterTimeout = keyRow(userId, key);
        assertThat(afterTimeout.get("recovery_point")).isEqualTo("customer_validated");
        assertThat(afterTimeout.get("response_code")).isNull();
        assertThat(afterTimeout.get("locked_at")).isNull();

        // 3. Final charge state: not yet recorded.
        assertThat(rideRow(id).get("status")).isEqualTo("pending");
        assertThat(rideRow(id).get("psp_charge_id")).isNull();

        IdempotencyOutcome retry = execute();

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(psp.uniqueCharges())
                .as("the retry hit the PSP's own idempotency cache via the derived key")
                .isEqualTo(1);
        assertThat(psp.totalInvocations()).isEqualTo(2);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isEqualTo(1);

        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("finished");
        assertThat(row.get("response_code")).isEqualTo(201);
        assertThat(row.get("attempt_no")).isEqualTo(2);
        assertThat(rideRow(id).get("status")).isEqualTo("charged");
        assertThat(rideRow(id).get("psp_charge_id")).isNotNull();

        // 4. Recovery path.
        assertThat(auditActions(id)).containsExactly(
                "key_created", "phase_committed", "lock_reclaimed",
                "phase_committed", "phase_committed");
    }

    @Test
    @DisplayName("F7b: ambiguous timeout, PSP did NOT charge → retry charges exactly once")
    void f7b_ambiguousTimeout_pspDidNotCharge() {
        psp.failNextCallBeforeCharging();

        assertThatThrownBy(this::execute)
                .isInstanceOf(FakeExternalPaymentClient.TransientPspException.class);

        long id = keyId(userId, key);

        // 1. Side effects: from the caller's seat this is indistinguishable
        //    from F7a, but no money moved.
        assertThat(psp.uniqueCharges()).isZero();
        assertThat(psp.totalInvocations()).isEqualTo(1);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isZero();

        // 2. Idempotency record: identical to F7a — same state, same lock
        //    release. The system does not need to know which case it is.
        Map<String, Object> afterTimeout = keyRow(userId, key);
        assertThat(afterTimeout.get("recovery_point")).isEqualTo("customer_validated");
        assertThat(afterTimeout.get("response_code")).isNull();
        assertThat(afterTimeout.get("locked_at")).isNull();

        // 3. Final charge state: pending.
        assertThat(rideRow(id).get("status")).isEqualTo("pending");
        assertThat(rideRow(id).get("psp_charge_id")).isNull();

        IdempotencyOutcome retry = execute();

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(psp.uniqueCharges())
                .as("exactly one charge — the first attempt never made it to the card")
                .isEqualTo(1);
        assertThat(psp.totalInvocations()).isEqualTo(2);
        assertThat(countRides()).isEqualTo(1);
        assertThat(countStagedJobs()).isEqualTo(1);

        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("finished");
        assertThat(row.get("response_code")).isEqualTo(201);
        assertThat(row.get("attempt_no")).isEqualTo(2);
        assertThat(rideRow(id).get("status")).isEqualTo("charged");
        assertThat(rideRow(id).get("psp_charge_id")).isNotNull();

        // 4. Recovery path.
        assertThat(auditActions(id)).containsExactly(
                "key_created", "phase_committed", "lock_reclaimed",
                "phase_committed", "phase_committed");
    }

    // =====================================================================
    // F8 — the duplicate arrives after the application has restarted.
    // =====================================================================
    @Test
    @DisplayName("F8: duplicate delivery after restart → served from Postgres alone")
    void f8_duplicateDeliveryAfterRestart() {
        IdempotencyOutcome original = execute();
        assertThat(original.statusCode()).isEqualTo(201);
        long id = keyId(userId, key);
        String originalChargeId = (String) rideRow(id).get("psp_charge_id");

        // A genuine restart: a second, independent application context against
        // the same database. Every in-memory structure is rebuilt from empty —
        // including the fake PSP's dedup store, so if the replay reached the
        // PSP at all it would charge a second time and this test would fail.
        try (ConfigurableApplicationContext restarted =
                new SpringApplicationBuilder(IdempotencyApplication.class)
                        .web(WebApplicationType.NONE)
                        .profiles("test")
                        // Passed as command-line args, not .properties(): the
                        // latter registers them as *default* properties, which
                        // application.yml would then override.
                        .run(
                                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                                "--spring.datasource.username=" + POSTGRES.getUsername(),
                                "--spring.datasource.password=" + POSTGRES.getPassword())) {

            IdempotencyService restartedService = restarted.getBean(IdempotencyService.class);
            FakeExternalPaymentClient restartedPsp =
                    restarted.getBean(FakeExternalPaymentClient.class);

            assertThat(restartedPsp.totalInvocations())
                    .as("the restarted instance starts with no memory of anything")
                    .isZero();

            IdempotencyOutcome replay = restartedService.execute(
                    userId, key, "POST", "/charges", CANONICAL, HASH);

            // 1. Side effects: none added by the replay, and the restarted
            //    instance never called the PSP.
            assertThat(restartedPsp.totalInvocations())
                    .as("the response came out of Postgres, not out of a call")
                    .isZero();
            assertThat(countRides()).isEqualTo(1);
            assertThat(countStagedJobs()).isEqualTo(1);

            // The contract survives the restart byte for byte.
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(original.body());
            assertThat(replay.body()).contains(originalChargeId);
        }

        // 2. Idempotency record: untouched terminal state.
        Map<String, Object> row = keyRow(userId, key);
        assertThat(row.get("recovery_point")).isEqualTo("finished");
        assertThat(row.get("response_code")).isEqualTo(201);
        assertThat(row.get("locked_at")).isNull();
        assertThat(row.get("attempt_no")).isEqualTo(1);

        // 3. Final charge state: the same charge as before the restart.
        Map<String, Object> ride = rideRow(id);
        assertThat(ride.get("status")).isEqualTo("charged");
        assertThat(ride.get("psp_charge_id")).isEqualTo(originalChargeId);

        // 4. Recovery path: a cache hit, recorded by the restarted instance.
        assertThat(auditActions(id)).containsExactly(
                "key_created",
                "phase_committed",   // started            -> customer_validated
                "phase_committed",   // customer_validated -> external_api_called
                "phase_committed",   // external_api_called-> finished
                "cache_hit");
    }
}
