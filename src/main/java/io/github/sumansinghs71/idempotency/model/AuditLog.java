package io.github.sumansinghs71.idempotency.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per state transition. See V1__schema.sql for column docs and the
 * list of {@code action} values.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key_id")
    private Long idempotencyKeyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "from_state", length = 50)
    private String fromState;

    @Column(name = "to_state", length = 50)
    private String toState;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo = 1;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb")
    private String metadata = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public AuditLog() {}

    public Long getId() { return id; }
    public Long getIdempotencyKeyId() { return idempotencyKeyId; }
    public Long getUserId() { return userId; }
    public String getAction() { return action; }
    public String getFromState() { return fromState; }
    public String getToState() { return toState; }
    public Integer getAttemptNo() { return attemptNo; }
    public String getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setIdempotencyKeyId(Long idempotencyKeyId) { this.idempotencyKeyId = idempotencyKeyId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setAction(String action) { this.action = action; }
    public void setFromState(String fromState) { this.fromState = fromState; }
    public void setToState(String toState) { this.toState = toState; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
