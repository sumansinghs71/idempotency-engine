package com.yourname.idempotency.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yourname.idempotency.api.ChargeRequest;
import com.yourname.idempotency.model.Ride;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Business logic outside the state machine — parses requests, builds
 * responses, picks pricing. Kept purely functional so it's trivial to test.
 */
@Service
public class ChargeService {

    private final ObjectMapper mapper;

    public ChargeService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ChargeRequest parse(String body) {
        try {
            return mapper.readValue(body, ChargeRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid charge request body", e);
        }
    }

    public String buildSuccessResponseBody(Ride ride, String chargeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ride_id", "rid_" + ride.getId());
        body.put("amount", ride.getAmountCents());
        body.put("currency", ride.getCurrency());
        body.put("charge_id", chargeId);
        body.put("status", "succeeded");
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build response body", e);
        }
    }

    public String buildDeclinedResponseBody(String declineCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "card_declined");
        body.put("decline_code", declineCode);
        body.put("message", message);
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build response body", e);
        }
    }
}
