package com.yourname.idempotency.service;

import com.yourname.idempotency.model.StagedJob;
import com.yourname.idempotency.repository.StagedJobRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactionally-staged job drain. Polls {@code staged_jobs} every 5
 * seconds, executes the job (currently a no-op stub that logs), and deletes
 * the row. Brandur's pattern: jobs become visible to this drain only after
 * the inserting transaction commits, so the receipt send is atomic with the
 * idempotency-key finalization.
 */
@Component
public class JobDrain {

    private static final Logger log = LoggerFactory.getLogger(JobDrain.class);

    private final StagedJobRepository repo;

    public JobDrain(StagedJobRepository repo) {
        this.repo = repo;
    }

    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT5S")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void drain() {
        List<StagedJob> jobs = repo.findAllOrdered();
        for (StagedJob job : jobs) {
            try {
                process(job);
                repo.delete(job);
            } catch (Exception e) {
                log.warn("Job {} ({}) failed; will retry next tick: {}",
                        job.getId(), job.getJobName(), e.getMessage());
            }
        }
    }

    private void process(StagedJob job) {
        // In production: SMTP / Mailgun / etc. Here we just log.
        log.info("Processing staged job id={} name={} args={}",
                job.getId(), job.getJobName(), job.getJobArgs());
    }
}
