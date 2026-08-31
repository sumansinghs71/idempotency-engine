package io.github.sumansinghs71.idempotency.service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory PSP for local dev and tests.
 *
 * <p>Mimics the property that matters most: a call carrying a known
 * {@code derivedIdempotencyKey} is deduplicated server-side. The first call
 * with a key creates a charge; subsequent calls with the same key return the
 * same charge id without recording another "real" charge.
 *
 * <p>Counts of total invocations vs. unique charges are exposed for tests so
 * they can assert "we only charged once".
 *
 * <p>Real production would talk to Stripe over HTTP here.
 */
@Component
@Profile("!real-psp")
public class FakeExternalPaymentClient implements ExternalPaymentClient {

    /** key -> stored result (charge id) */
    private final ConcurrentMap<String, PspChargeResult> store = new ConcurrentHashMap<>();
    private final AtomicInteger totalInvocations = new AtomicInteger();
    private final AtomicInteger uniqueCharges = new AtomicInteger();

    /** Test hook: when set, the next call throws AFTER recording the charge. */
    private volatile boolean failAfterCharge = false;
    /** Test hook: when set, the next call throws BEFORE recording the charge. */
    private volatile boolean failBeforeCharge = false;
    private volatile boolean failureWasAfterCharge = false;

    /** Test hook: when not null, declines with this code regardless of input. */
    private volatile String forcedDeclineCode = null;

    /** Simulated latency in ms for benchmarking. */
    private final long latencyMs;

    public FakeExternalPaymentClient(
            @Value("${psp.fake-latency-ms:0}") long latencyMs) {
        this.latencyMs = latencyMs;
    }

    @Override
    public PspChargeResult charge(
            long amountCents,
            String currency,
            String pspCustomerId,
            String derivedIdempotencyKey) {
        if (derivedIdempotencyKey == null || derivedIdempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "derivedIdempotencyKey is required — nested idempotency must not be skipped");
        }
        totalInvocations.incrementAndGet();
        if (latencyMs > 0) {
            try { Thread.sleep(latencyMs); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        // Critical: dedup by derivedIdempotencyKey BEFORE the failure injection,
        // so that a retried call after a simulated failure hits the cache and
        // does not produce a second charge — same as Stripe's PSP cache.
        PspChargeResult cached = store.get(derivedIdempotencyKey);
        if (cached != null) {
            return cached;
        }

        if (failBeforeCharge) {
            failBeforeCharge = false;
            failureWasAfterCharge = false;
            // The ambiguous case where the card was NOT charged. The caller
            // cannot tell this apart from the after-charge case below — which
            // is precisely why the retry must carry the same derived key.
            throw new TransientPspException(
                    "Simulated network failure before the charge reached the PSP");
        }

        if (forcedDeclineCode != null) {
            PspChargeResult declined = PspChargeResult.declined(forcedDeclineCode, "Card declined");
            store.put(derivedIdempotencyKey, declined);
            return declined;
        }

        // Persist BEFORE the simulated failure — mirrors real life where the
        // PSP charges the card and only then loses the response packet.
        String chargeId = "ch_" + UUID.randomUUID();
        PspChargeResult result = PspChargeResult.succeeded(chargeId);
        store.put(derivedIdempotencyKey, result);
        uniqueCharges.incrementAndGet();

        if (failAfterCharge) {
            failAfterCharge = false;
            failureWasAfterCharge = true;
            throw new TransientPspException(
                    "Simulated network failure after charge was committed at PSP");
        }
        return result;
    }

    public int totalInvocations() { return totalInvocations.get(); }
    public int uniqueCharges() { return uniqueCharges.get(); }
    public void resetCounters() { totalInvocations.set(0); uniqueCharges.set(0); }

    /**
     * Full reset — counters, injected failures, forced decline, and the dedup
     * store. Tests must call this between cases: the store is keyed by the
     * derived key {@code "idem-" + rowId}, so leaving it populated lets one
     * test's charge answer another test's call and silently turn a real charge
     * into a cache hit.
     */
    public void reset() {
        store.clear();
        resetCounters();
        clearFailureInjection();
        clearForcedDecline();
    }
    public void failNextCallAfterCharging() {
        this.failAfterCharge = true;
        this.failBeforeCharge = false;
        this.failureWasAfterCharge = false;
    }
    public void failNextCallBeforeCharging() {
        this.failBeforeCharge = true;
        this.failAfterCharge = false;
        this.failureWasAfterCharge = false;
    }
    public void clearFailureInjection() {
        this.failAfterCharge = false;
        this.failBeforeCharge = false;
        this.failureWasAfterCharge = false;
    }
    public boolean wasFailedAfterCharge() { return failureWasAfterCharge; }
    public void forceDecline(String code) { this.forcedDeclineCode = code; }
    public void clearForcedDecline() { this.forcedDeclineCode = null; }

    /** Transient (retryable) PSP error. */
    public static class TransientPspException extends RuntimeException {
        public TransientPspException(String msg) { super(msg); }
    }
}
