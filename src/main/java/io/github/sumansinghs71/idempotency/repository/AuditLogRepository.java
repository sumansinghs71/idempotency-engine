package io.github.sumansinghs71.idempotency.repository;

import io.github.sumansinghs71.idempotency.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Inserts an audit row, deduping on the
     * {@code (idempotency_key_id, attempt_no, action, to_state)} unique
     * constraint. This makes audit writes idempotent across phase retries.
     */
    @Modifying
    @Query(value =
            "INSERT INTO audit_logs "
                    + "(idempotency_key_id, user_id, action, from_state, to_state, "
                    + " attempt_no, metadata, created_at) "
                    + "VALUES (:keyId, :userId, :action, :fromState, :toState, "
                    + " :attemptNo, CAST(:metadata AS jsonb), NOW()) "
                    + "ON CONFLICT (idempotency_key_id, attempt_no, action, to_state) "
                    + "DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("keyId") Long keyId,
            @Param("userId") long userId,
            @Param("action") String action,
            @Param("fromState") String fromState,
            @Param("toState") String toState,
            @Param("attemptNo") int attemptNo,
            @Param("metadata") String metadata);
}
