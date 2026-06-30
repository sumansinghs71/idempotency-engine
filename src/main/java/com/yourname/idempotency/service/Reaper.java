package com.yourname.idempotency.service;

import com.yourname.idempotency.repository.IdempotencyKeyRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Hourly sweep that purges expired idempotency rows. */
@Component
public class Reaper {

    private static final Logger log = LoggerFactory.getLogger(Reaper.class);

    private final IdempotencyKeyRepository keyRepo;
    private final MeterRegistry meter;

    public Reaper(IdempotencyKeyRepository keyRepo, MeterRegistry meter) {
        this.keyRepo = keyRepo;
        this.meter = meter;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    @Transactional
    public void reap() {
        int deleted = keyRepo.deleteExpired(Instant.now());
        if (deleted > 0) {
            meter.counter("idem.reaper.rows_deleted").increment(deleted);
            log.info("Reaper deleted {} expired idempotency rows", deleted);
        }
    }
}
