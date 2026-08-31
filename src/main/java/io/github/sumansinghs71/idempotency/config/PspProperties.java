package io.github.sumansinghs71.idempotency.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** External PSP configuration. */
@ConfigurationProperties(prefix = "psp")
public record PspProperties(String baseUrl, long timeoutMs) {

    public PspProperties {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:9999";
        if (timeoutMs <= 0) timeoutMs = 80_000L;
    }
}
