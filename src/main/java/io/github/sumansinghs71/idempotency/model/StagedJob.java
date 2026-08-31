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

@Entity
@Table(name = "staged_jobs")
public class StagedJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key_id")
    private Long idempotencyKeyId;

    @Column(name = "job_name", nullable = false, length = 50)
    private String jobName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "job_args", nullable = false, columnDefinition = "jsonb")
    private String jobArgs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public StagedJob() {}

    public Long getId() { return id; }
    public Long getIdempotencyKeyId() { return idempotencyKeyId; }
    public String getJobName() { return jobName; }
    public String getJobArgs() { return jobArgs; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setIdempotencyKeyId(Long idempotencyKeyId) { this.idempotencyKeyId = idempotencyKeyId; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public void setJobArgs(String jobArgs) { this.jobArgs = jobArgs; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
