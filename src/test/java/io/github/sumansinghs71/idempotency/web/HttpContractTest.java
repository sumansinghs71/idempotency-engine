package io.github.sumansinghs71.idempotency.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.sumansinghs71.idempotency.AbstractPostgresIT;
import io.github.sumansinghs71.idempotency.service.FakeExternalPaymentClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The HTTP surface: header validation, status codes, and the exact bytes the
 * client sees on a retry.
 *
 * <p>Also pins the {@code X-User-Id} behaviour that the README describes as a
 * <b>development/demo identity shim</b>: it is an unverified, client-supplied
 * header, and {@link #xUserIdIsAnUnverifiedDemoShim()} demonstrates that
 * anyone can assume any identity by changing it. That test exists to keep the
 * documentation honest — it asserts the weakness, it does not endorse it.
 */
class HttpContractTest extends AbstractPostgresIT {

    @Autowired WebApplicationContext wac;
    @Autowired FakeExternalPaymentClient psp;

    private MockMvc mvc;
    private long userId;

    private static final String BODY =
            "{\"amount\":2000,\"currency\":\"usd\",\"customer_id\":\"cus_http\"}";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).build();
        // Full reset: counters, injected failures, and the PSP's own dedup store.
        psp.reset();
        userId = seedUser("http-" + UUID.randomUUID() + "@example.com");
    }

    private MvcResult charge(String key, Long user, String body) throws Exception {
        var req = post("/charges").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        if (user != null) {
            req = req.header("X-User-Id", String.valueOf(user));
        }
        return mvc.perform(req).andReturn();
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("POST /charges with a key and a body → 201")
    void happyPathOverHttp() throws Exception {
        MvcResult res = charge(UUID.randomUUID().toString(), userId, BODY);

        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        assertThat(jsonField(res.getResponse().getContentAsString(), "status"))
                .isEqualTo("succeeded");
        assertThat(countRides()).isEqualTo(1);
        assertThat(psp.uniqueCharges()).isEqualTo(1);
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("A retried POST returns the identical bytes and charges once")
    void retryReturnsIdenticalBytes() throws Exception {
        String key = UUID.randomUUID().toString();

        MvcResult first = charge(key, userId, BODY);
        MvcResult second = charge(key, userId, BODY);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(psp.uniqueCharges()).isEqualTo(1);
        assertThat(psp.totalInvocations()).isEqualTo(1);
        assertThat(countRides()).isEqualTo(1);
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("Missing Idempotency-Key → 400, and nothing is charged")
    void missingIdempotencyKeyRejected() throws Exception {
        MvcResult res = charge(null, userId, BODY);

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("idempotency_key_required");
        assertThat(psp.totalInvocations()).isZero();
        assertThat(countRides()).isZero();
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("Idempotency-Key longer than the column → 400")
    void oversizeIdempotencyKeyRejected() throws Exception {
        MvcResult res = charge("k".repeat(101), userId, BODY);

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("idempotency_key_invalid");
        assertThat(countRides()).isZero();
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("A body that is not JSON → 400 before any state is created")
    void invalidJsonRejected() throws Exception {
        MvcResult res = charge(UUID.randomUUID().toString(), userId, "not json at all");

        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("invalid_json");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Long.class))
                .isZero();
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("Same key + different body over HTTP → 422")
    void bodyMismatchOverHttp() throws Exception {
        String key = UUID.randomUUID().toString();
        charge(key, userId, BODY);

        MvcResult res = charge(
                key, userId, "{\"amount\":9999,\"currency\":\"usd\",\"customer_id\":\"cus_http\"}");

        assertThat(res.getResponse().getStatus()).isEqualTo(422);
        assertThat(res.getResponse().getContentAsString())
                .contains("idempotency_key_body_mismatch");
        assertThat(psp.uniqueCharges()).isEqualTo(1);
    }

    // -------------------------------------------------------------------
    @Test
    @DisplayName("X-User-Id is an unverified demo shim: absent → 400-class, forged → accepted")
    void xUserIdIsAnUnverifiedDemoShim() throws Exception {
        // Absent: the request is rejected, but only because the state machine
        // has no user id to scope the key by — not because anything was authenticated.
        MvcResult missing = charge(UUID.randomUUID().toString(), null, BODY);
        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        assertThat(missing.getResponse().getContentAsString()).contains("unauthorized");

        // Non-numeric: same input-validation rejection.
        MvcResult garbage = mvc.perform(
                        post("/charges")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .header("X-User-Id", "not-a-number")
                                .content(BODY))
                .andReturn();
        assertThat(garbage.getResponse().getStatus()).isEqualTo(401);

        // The point of this test: the header is taken at face value. A second
        // seeded user's id, supplied by the caller with no credential of any
        // kind, is accepted and scopes the request to that user. This is the
        // documented weakness of the shim, asserted so the README cannot drift
        // into calling it authentication.
        long otherUser = seedUser("victim-" + UUID.randomUUID() + "@example.com");
        String key = "same-key-string";

        assertThat(charge(key, userId, BODY).getResponse().getStatus()).isEqualTo(201);
        assertThat(charge(key, otherUser, BODY).getResponse().getStatus()).isEqualTo(201);

        // Two independent key rows, two charges — the header alone chose which
        // tenant's namespace was written to.
        assertThat(keyId(userId, key)).isNotEqualTo(keyId(otherUser, key));
        assertThat(psp.uniqueCharges()).isEqualTo(2);
    }
}
