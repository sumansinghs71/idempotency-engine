package com.yourname.idempotency.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The row in {@code idempotency_keys}. See V1__schema.sql for column docs. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key", nullable = false, length = 100)
    private String key;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_method", nullable = false, length = 10)
    private String requestMethod;

    @Column(name = "request_path", nullable = false, length = 255)
    private String requestPath;

    @Column(name = "request_params_hash", nullable = false, length = 64)
    private String requestParamsHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_body", nullable = false, columnDefinition = "jsonb")
    private String requestBody;

    @Column(name = "response_code")
    private Integer responseCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private String responseBody;

    @Column(name = "recovery_point", nullable = false, length = 50)
    private String recoveryPoint = RecoveryPoint.STARTED.dbValue();

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo = 1;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // Note: no @Version field. We use the row-level pessimistic lock
    // (SELECT … FOR UPDATE) and locked_at for concurrency; an additional
    // optimistic-locking column would be redundant.

    public IdempotencyKey() {}

    public Long getId() { return id; }
    public String getKey() { return key; }
    public Long getUserId() { return userId; }
    public String getRequestMethod() { return requestMethod; }
    public String getRequestPath() { return requestPath; }
    public String getRequestParamsHash() { return requestParamsHash; }
    public String getRequestBody() { return requestBody; }
    public Integer getResponseCode() { return responseCode; }
    public String getResponseBody() { return responseBody; }
    public String getRecoveryPoint() { return recoveryPoint; }
    public Instant getLockedAt() { return lockedAt; }
    public Integer getAttemptNo() { return attemptNo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public RecoveryPoint recoveryPointEnum() {
        return RecoveryPoint.fromDb(recoveryPoint);
    }

    public void setId(Long id) { this.id = id; }
    public void setKey(String key) { this.key = key; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setRequestMethod(String requestMethod) { this.requestMethod = requestMethod; }
    public void setRequestPath(String requestPath) { this.requestPath = requestPath; }
    public void setRequestParamsHash(String requestParamsHash) { this.requestParamsHash = requestParamsHash; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }
    public void setResponseCode(Integer responseCode) { this.responseCode = responseCode; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public void setRecoveryPoint(String recoveryPoint) { this.recoveryPoint = recoveryPoint; }
    public void setRecoveryPoint(RecoveryPoint rp) { this.recoveryPoint = rp.dbValue(); }
    public void setLockedAt(Instant lockedAt) { this.lockedAt = lockedAt; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
