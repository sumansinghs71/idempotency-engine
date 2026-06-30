package com.yourname.idempotency.service;

/**
 * The downstream PSP boundary.
 *
 * <p>Every external charge call <strong>must</strong> carry a derived
 * idempotency key as a required parameter. The derived key is constructed by
 * the caller from the inbound key row id ({@code "idem-" + rowId}) so that it
 * is identical on every retry of the same logical request. The PSP uses this
 * key to deduplicate retried calls — without it, a retry after a network blip
 * would double-charge the customer.
 *
 * <p>This is the "nested idempotency" property described in
 * https://brandur.org/idempotency-keys §"Calling Stripe".
 */
public interface ExternalPaymentClient {

    /**
     * Charge a customer.
     *
     * @param amountCents amount in the smallest currency unit
     * @param currency ISO 4217 code (e.g. "usd")
     * @param pspCustomerId opaque customer reference at the PSP
     * @param derivedIdempotencyKey REQUIRED; must be deterministic across
     *     retries of the same logical request
     * @return result with charge id and outcome
     */
    PspChargeResult charge(
            long amountCents,
            String currency,
            String pspCustomerId,
            String derivedIdempotencyKey);
}
