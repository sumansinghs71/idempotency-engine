package com.yourname.idempotency.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the idempotency state machine. See TRD §2.4 (TTL) and §2.5
 * (lock staleness).
 */
@ConfigurationProperties(prefix = "idempotency")
public record IdempotencyProperties(
        /** TTL after which a key row is reclaimable as if it never existed. */
        Duration ttl,
        /**
         * If a row's locked_at is older than this, the previous holder is
         * presumed dead and a retry may reclaim. Must exceed the PSP timeout.
         */
        Duration lockStaleness,
        /** Max length of the client-supplied key, matching the column. */
        int maxKeyLength) {

    public IdempotencyProperties {
        if (ttl == null) ttl = Duration.ofHours(24);
        if (lockStaleness == null) lockStaleness = Duration.ofSeconds(90);
        if (maxKeyLength <= 0) maxKeyLength = 100;
    }
}
