package com.yourname.idempotency.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.idempotency.api.ChargeRequest;
import com.yourname.idempotency.config.IdempotencyProperties;
import com.yourname.idempotency.model.IdempotencyKey;
import com.yourname.idempotency.model.RecoveryPoint;
import com.yourname.idempotency.model.Ride;
import com.yourname.idempotency.model.User;
import com.yourname.idempotency.repository.AuditLogRepository;
import com.yourname.idempotency.repository.IdempotencyKeyRepository;
import com.yourname.idempotency.repository.RideRepository;
import com.yourname.idempotency.repository.StagedJobRepository;
import com.yourname.idempotency.repository.UserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holder for the four phase transactions plus the acquire transaction.
 *
 * <p>Every method here is {@link Propagation#REQUIRES_NEW} — that is the
 * load-bearing detail. Recovery-point commits must NOT be rolled back with
 * any outer transaction, and each phase commits independently so a crash
 * between phases leaves the recovery point correctly persisted for the
 * resuming attempt.
 *
 * <p>Kept on a separate Spring bean from {@link IdempotencyService} so that
 * the {@code @Transactional} proxies work correctly across method calls.
 */
@Service
public class PhaseTransactions {

    private static final Logger log = LoggerFactory.getLogger(PhaseTransactions.class);

    private final IdempotencyKeyRepository keyRepo;
    private final UserRepository userRepo;
    private final RideRepository rideRepo;
    private final StagedJobRepository stagedJobRepo;
    private final AuditLogRepository auditRepo;
    private final ChargeService chargeService;
    private final IdempotencyProperties props;
    private final ObjectMapper mapper;

    public PhaseTransactions(
            IdempotencyKeyRepository keyRepo,
            UserRepository userRepo,
            RideRepository rideRepo,
            StagedJobRepository stagedJobRepo,
            AuditLogRepository auditRepo,
            ChargeService chargeService,
            IdempotencyProperties props,
            ObjectMapper mapper) {
        this.keyRepo = keyRepo;
        this.userRepo = userRepo;
        this.rideRepo = rideRepo;
        this.stagedJobRepo = stagedJobRepo;
        this.auditRepo = auditRepo;
        this.chargeService = chargeService;
        this.props = props;
        this.mapper = mapper;
    }

    // -----------------------------------------------------------------------
    // tx1 — acquire or serve cache
    // -----------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AcquireResult acquireOrServe(
            long userId, String key, String method, String path,
            String requestBody, String requestBodyHash) {
        Instant now = Instant.now();
        Optional<IdempotencyKey> existing = keyRepo.findForUpdate(userId, key, now);

        if (existing.isEmpty()) {
            // Insert a fresh row. The unique constraint on (user_id, key) makes
            // this safe under a concurrent insert race: the loser sees a
            // ConstraintViolation and falls through to a retry.
            IdempotencyKey row = new IdempotencyKey();
            row.setKey(key);
            row.setUserId(userId);
            row.setRequestMethod(method);
            row.setRequestPath(path);
            row.setRequestParamsHash(requestBodyHash);
            row.setRequestBody(requestBody);
            row.setRecoveryPoint(RecoveryPoint.STARTED);
            row.setLockedAt(now);
            row.setAttemptNo(1);
            row.setCreatedAt(now);
            row.setExpiresAt(now.plus(props.ttl()));
            try {
                IdempotencyKey saved = keyRepo.saveAndFlush(row);
                auditRepo.insertIfAbsent(
                        saved.getId(), userId, "key_created", null,
                        RecoveryPoint.STARTED.dbValue(), 1, "{}");
                return new AcquireResult.Fresh(saved.getId());
            } catch (DataIntegrityViolationException e) {
                // Discriminate by Postgres SQLState:
                //   23505 unique_violation       → genuine concurrent-INSERT race; retry.
                //   23503 foreign_key_violation  → bad input (e.g. unknown user_id); surface it.
                //   anything else                → unknown integrity problem; propagate.
                String sqlState = sqlStateOf(e);
                if ("23505".equals(sqlState)) {
                    throw new ConcurrentInsertException();
                }
                if ("23503".equals(sqlState)) {
                    throw new UnknownReferenceException(
                            "Foreign-key violation creating idempotency key — "
                                    + "likely unknown user_id=" + userId, e);
                }
                throw e;
            }
        }

        IdempotencyKey row = existing.get();

        // Body fingerprint check — applies to any state, including finished.
        if (!row.getRequestParamsHash().equals(requestBodyHash)) {
            auditRepo.insertIfAbsent(
                    row.getId(), userId, "body_mismatch",
                    row.getRecoveryPoint(), null, row.getAttemptNo(), "{}");
            return new AcquireResult.BodyMismatch();
        }

        RecoveryPoint rp = row.recoveryPointEnum();
        if (rp == RecoveryPoint.FINISHED) {
            auditRepo.insertIfAbsent(
                    row.getId(), userId, "cache_hit",
                    "finished", "finished", row.getAttemptNo(), "{}");
            return new AcquireResult.Cached(row.getResponseCode(), row.getResponseBody());
        }

        // Non-finished: check the lock.
        if (row.getLockedAt() != null
                && row.getLockedAt().isAfter(now.minus(props.lockStaleness()))) {
            // Holder still alive.
            auditRepo.insertIfAbsent(
                    row.getId(), userId, "lock_conflict",
                    row.getRecoveryPoint(), null, row.getAttemptNo(), "{}");
            // 500 ms retry-after is a reasonable conservative default.
            return new AcquireResult.Conflict(500L);
        }

        // Reclaim — stale lock or unlocked non-finished row.
        int attemptNo = row.getAttemptNo() + 1;
        keyRepo.markAcquired(row.getId(), now);
        auditRepo.insertIfAbsent(
                row.getId(), userId, "lock_reclaimed",
                row.getRecoveryPoint(), row.getRecoveryPoint(), attemptNo, "{}");
        return new AcquireResult.Resumed(row.getId(), rp);
    }

    // -----------------------------------------------------------------------
    // tx2 — STARTED → CUSTOMER_VALIDATED
    // -----------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryPoint runStartedPhase(long keyId) {
        IdempotencyKey key = keyRepo.findById(keyId).orElseThrow();
        ChargeRequest req = chargeService.parse(key.getRequestBody());

        User user = userRepo.findById(key.getUserId()).orElseThrow(
                () -> new IllegalStateException("user not found: " + key.getUserId()));

        // Insert ride row only if it doesn't already exist (idempotent on retry
        // resuming from STARTED after tx2 was rolled back). The (user_id,
        // idempotency_key_id) UNIQUE constraint guards against duplicates.
        Optional<Ride> existing = rideRepo.findByIdempotencyKeyId(keyId);
        Ride ride;
        if (existing.isPresent()) {
            ride = existing.get();
        } else {
            ride = new Ride();
            ride.setIdempotencyKeyId(keyId);
            ride.setUserId(user.getId());
            ride.setAmountCents(req.amount());
            ride.setCurrency(req.currency());
            ride.setStatus("pending");
            ride = rideRepo.saveAndFlush(ride);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("ride_id", ride.getId());
        auditRepo.insertIfAbsent(
                keyId, key.getUserId(), "phase_committed",
                RecoveryPoint.STARTED.dbValue(),
                RecoveryPoint.CUSTOMER_VALIDATED.dbValue(),
                key.getAttemptNo(), toJson(meta));

        keyRepo.updateRecoveryPoint(keyId, RecoveryPoint.CUSTOMER_VALIDATED.dbValue());
        return RecoveryPoint.CUSTOMER_VALIDATED;
    }

    // -----------------------------------------------------------------------
    // tx3 — CUSTOMER_VALIDATED → EXTERNAL_API_CALLED
    //
    // Special-cased: the external PSP call happens OUTSIDE this transaction
    // (we don't want to hold DB resources across the network). The orchestrator
    // calls the PSP, then calls persistExternalCallResult to commit the
    // outcome. If the JVM dies between PSP success and this commit, the retry
    // re-invokes the PSP with the same derived key and gets the cached charge.
    // -----------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryPoint persistExternalCallResult(
            long keyId, PspChargeResult result, String derivedKey) {
        IdempotencyKey key = keyRepo.findById(keyId).orElseThrow();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("derived_key", derivedKey);
        meta.put("outcome", result.outcome().name());

        switch (result.outcome()) {
            case SUCCEEDED -> {
                rideRepo.updateChargeResult(keyId, result.chargeId(), "charged");
                meta.put("psp_charge_id", result.chargeId());
                auditRepo.insertIfAbsent(
                        keyId, key.getUserId(), "phase_committed",
                        RecoveryPoint.CUSTOMER_VALIDATED.dbValue(),
                        RecoveryPoint.EXTERNAL_API_CALLED.dbValue(),
                        key.getAttemptNo(), toJson(meta));
                keyRepo.updateRecoveryPoint(
                        keyId, RecoveryPoint.EXTERNAL_API_CALLED.dbValue());
                return RecoveryPoint.EXTERNAL_API_CALLED;
            }
            case CARD_DECLINED -> {
                rideRepo.updateChargeResult(keyId, null, "declined");
                meta.put("decline_code", result.declineCode());
                auditRepo.insertIfAbsent(
                        keyId, key.getUserId(), "phase_committed",
                        RecoveryPoint.CUSTOMER_VALIDATED.dbValue(),
                        RecoveryPoint.FINISHED.dbValue(),
                        key.getAttemptNo(), toJson(meta));
                // Cache the 402 response and short-circuit to finished.
                String responseBody = chargeService.buildDeclinedResponseBody(
                        result.declineCode(), result.message());
                keyRepo.markFinished(keyId, 402, responseBody,
                        RecoveryPoint.FINISHED.dbValue());
                return RecoveryPoint.FINISHED;
            }
            default -> throw new IllegalStateException(
                    "TRANSIENT_FAILURE must be raised as an exception, not persisted");
        }
    }

    // -----------------------------------------------------------------------
    // tx4 — EXTERNAL_API_CALLED → FINISHED
    // -----------------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecoveryPoint runFinalizePhase(long keyId) {
        IdempotencyKey key = keyRepo.findById(keyId).orElseThrow();
        Ride ride = rideRepo.findByIdempotencyKeyId(keyId).orElseThrow(
                () -> new IllegalStateException(
                        "ride not found for keyId=" + keyId + " — recovery-point inconsistency"));

        // Stage the receipt job. ON CONFLICT DO NOTHING means retrying this
        // phase doesn't enqueue twice.
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("ride_id", ride.getId());
        args.put("user_id", ride.getUserId());
        args.put("amount_cents", ride.getAmountCents());
        args.put("currency", ride.getCurrency());
        args.put("charge_id", ride.getPspChargeId());
        stagedJobRepo.stageIfAbsent(keyId, "send_ride_receipt", toJson(args));

        String responseBody = chargeService.buildSuccessResponseBody(
                ride, ride.getPspChargeId());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("response_code", 201);
        auditRepo.insertIfAbsent(
                keyId, key.getUserId(), "phase_committed",
                RecoveryPoint.EXTERNAL_API_CALLED.dbValue(),
                RecoveryPoint.FINISHED.dbValue(),
                key.getAttemptNo(), toJson(meta));

        keyRepo.markFinished(keyId, 201, responseBody, RecoveryPoint.FINISHED.dbValue());
        return RecoveryPoint.FINISHED;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public IdempotencyKey snapshot(long keyId) {
        return keyRepo.findById(keyId).orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseLockOnError(long keyId) {
        try {
            IdempotencyKey row = keyRepo.findById(keyId).orElse(null);
            if (row == null) return;
            row.setLockedAt(null);
            keyRepo.save(row);
        } catch (Exception e) {
            log.warn("Failed to release lock on key {} after error: {}",
                    keyId, e.getMessage());
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return mapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Thrown by acquireOrServe when a concurrent INSERT wins the race. */
    public static class ConcurrentInsertException extends RuntimeException {
        public ConcurrentInsertException() {
            super("concurrent insert detected; retry acquire");
        }
    }

    /** Thrown when a FK target (e.g. user_id) is missing — bad input, not a race. */
    public static class UnknownReferenceException extends RuntimeException {
        public UnknownReferenceException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** Walks the cause chain to find the underlying SQLException and pulls SQLState. */
    private static String sqlStateOf(Throwable t) {
        Throwable c = t;
        while (c != null) {
            if (c instanceof java.sql.SQLException sqle) {
                return sqle.getSQLState();
            }
            c = c.getCause();
        }
        return null;
    }
}
