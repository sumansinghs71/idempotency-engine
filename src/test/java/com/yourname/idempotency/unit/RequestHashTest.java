package com.yourname.idempotency.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.yourname.idempotency.model.RequestHash;
import org.junit.jupiter.api.Test;

class RequestHashTest {

    @Test
    void canonicalizationIsKeyOrderInsensitive() {
        String a = RequestHash.canonicalize("{\"a\":1,\"b\":2}");
        String b = RequestHash.canonicalize("{\"b\":2,\"a\":1}");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void hashesMatchAcrossKeyOrders() {
        String h1 = RequestHash.sha256OfCanonicalized("{\"amount\":2000,\"currency\":\"usd\"}");
        String h2 = RequestHash.sha256OfCanonicalized("{\"currency\":\"usd\",\"amount\":2000}");
        assertThat(h1).isEqualTo(h2).hasSize(64);
    }

    @Test
    void hashChangesWhenValueChanges() {
        String h1 = RequestHash.sha256OfCanonicalized("{\"amount\":2000}");
        String h2 = RequestHash.sha256OfCanonicalized("{\"amount\":3000}");
        assertThat(h1).isNotEqualTo(h2);
    }
}
