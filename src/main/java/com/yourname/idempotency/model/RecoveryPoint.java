package com.yourname.idempotency.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * The DAG of recovery points the state machine moves through.
 *
 * <p>Each phase commits the {@code idempotency_keys.recovery_point} column to
 * the *next* state in the same transaction as the phase's local mutations. On
 * crash + retry, the state machine resumes from whatever recovery point is
 * persisted.
 *
 * <p>Adding a new phase: append only. Older code that encounters an unknown
 * recovery point must hard-fail (see {@link #fromDb(String)}) — silently
 * skipping is a correctness bug.
 */
public enum RecoveryPoint {
    STARTED("started"),
    CUSTOMER_VALIDATED("customer_validated"),
    EXTERNAL_API_CALLED("external_api_called"),
    FINISHED("finished");

    private static final Set<RecoveryPoint> TERMINAL = EnumSet.of(FINISHED);

    private final String dbValue;

    RecoveryPoint(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static RecoveryPoint fromDb(String value) {
        for (RecoveryPoint rp : values()) {
            if (rp.dbValue.equals(value)) return rp;
        }
        throw new IllegalStateException(
                "Unknown recovery_point '" + value + "' in DB. "
                        + "Possible older/newer app version mismatch.");
    }
}
