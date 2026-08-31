package io.github.sumansinghs71.idempotency.repository;

import io.github.sumansinghs71.idempotency.model.IdempotencyKey;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    /**
     * Acquires the row-level lock via {@code SELECT … FOR UPDATE}.
     *
     * <p>Note: the predicate uses {@code expires_at > now()} so an expired row
     * is invisible from the request path (the reaper will delete it).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM IdempotencyKey k "
            + "WHERE k.userId = :userId AND k.key = :key "
            + "AND k.expiresAt > :now")
    Optional<IdempotencyKey> findForUpdate(
            @Param("userId") long userId,
            @Param("key") String key,
            @Param("now") Instant now);

    Optional<IdempotencyKey> findByUserIdAndKey(long userId, String key);

    @Modifying
    @Query("UPDATE IdempotencyKey k SET k.lockedAt = :lockedAt, "
            + "k.attemptNo = k.attemptNo + 1 WHERE k.id = :id")
    int markAcquired(@Param("id") long id, @Param("lockedAt") Instant lockedAt);

    @Modifying
    @Query("UPDATE IdempotencyKey k SET k.recoveryPoint = :recoveryPoint "
            + "WHERE k.id = :id")
    int updateRecoveryPoint(
            @Param("id") long id, @Param("recoveryPoint") String recoveryPoint);

    @Modifying
    @Query("UPDATE IdempotencyKey k SET "
            + "k.responseCode = :code, k.responseBody = :body, "
            + "k.recoveryPoint = :recoveryPoint, k.lockedAt = NULL "
            + "WHERE k.id = :id")
    int markFinished(
            @Param("id") long id,
            @Param("code") int code,
            @Param("body") String body,
            @Param("recoveryPoint") String recoveryPoint);

    @Modifying
    @Query(value = "DELETE FROM idempotency_keys WHERE expires_at < :cutoff",
            nativeQuery = true)
    int deleteExpired(@Param("cutoff") Instant cutoff);

    /**
     * Deletes the expired row for one {@code (user_id, key)} pair, if any.
     *
     * <p>Needed because {@code findForUpdate} filters on {@code expires_at >
     * now()}: once a row expires it is invisible to the request path, but the
     * {@code UNIQUE (user_id, key)} index still contains it, so the INSERT of a
     * replacement fails with 23505. Without this the key would be permanently
     * unusable in the window between expiry and the hourly reaper sweep.
     *
     * <p>The {@code rides} / {@code audit_logs} / {@code staged_jobs} foreign
     * keys are {@code ON DELETE SET NULL}, so the historical business rows
     * survive the delete with a NULL key id.
     */
    @Modifying
    @Query(value = "DELETE FROM idempotency_keys "
            + "WHERE user_id = :userId AND key = :key AND expires_at <= :now",
            nativeQuery = true)
    int deleteExpiredForKey(
            @Param("userId") long userId,
            @Param("key") String key,
            @Param("now") Instant now);
}
