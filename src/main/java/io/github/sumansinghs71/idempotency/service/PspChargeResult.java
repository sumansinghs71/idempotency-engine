package io.github.sumansinghs71.idempotency.service;

/** Result from {@link ExternalPaymentClient#charge}. */
public record PspChargeResult(Outcome outcome, String chargeId, String declineCode, String message) {

    public enum Outcome { SUCCEEDED, CARD_DECLINED, TRANSIENT_FAILURE }

    public static PspChargeResult succeeded(String chargeId) {
        return new PspChargeResult(Outcome.SUCCEEDED, chargeId, null, null);
    }

    public static PspChargeResult declined(String declineCode, String message) {
        return new PspChargeResult(Outcome.CARD_DECLINED, null, declineCode, message);
    }
}
