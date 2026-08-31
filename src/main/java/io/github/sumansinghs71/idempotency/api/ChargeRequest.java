package io.github.sumansinghs71.idempotency.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ChargeRequest(
        long amount,
        String currency,
        @JsonProperty("customer_id") String customerId) {

    @JsonCreator
    public ChargeRequest(
            @JsonProperty("amount") long amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("customer_id") String customerId) {
        this.amount = amount;
        this.currency = currency == null ? "usd" : currency.toLowerCase();
        this.customerId = customerId;
    }
}
