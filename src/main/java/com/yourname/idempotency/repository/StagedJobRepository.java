package com.yourname.idempotency.repository;

import com.yourname.idempotency.model.StagedJob;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StagedJobRepository extends JpaRepository<StagedJob, Long> {

    /**
     * Insert with {@code ON CONFLICT DO NOTHING} on
     * {@code (idempotency_key_id, job_name)} so phase 4 can re-run safely on
     * crash retry.
     */
    @Modifying
    @Query(value =
            "INSERT INTO staged_jobs (idempotency_key_id, job_name, job_args, created_at) "
                    + "VALUES (:keyId, :jobName, CAST(:jobArgs AS jsonb), NOW()) "
                    + "ON CONFLICT (idempotency_key_id, job_name) DO NOTHING",
            nativeQuery = true)
    int stageIfAbsent(
            @Param("keyId") Long keyId,
            @Param("jobName") String jobName,
            @Param("jobArgs") String jobArgs);

    @Query("SELECT j FROM StagedJob j ORDER BY j.createdAt ASC")
    List<StagedJob> findAllOrdered();
}
