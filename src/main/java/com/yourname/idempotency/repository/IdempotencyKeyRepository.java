package com.yourname.idempotency.repository;

import com.yourname.idempotency.model.IdempotencyKey;
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
}
