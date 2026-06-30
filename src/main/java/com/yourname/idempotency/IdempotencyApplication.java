package com.yourname.idempotency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * IdemEngine — Stripe-style idempotency middleware.
 *
 * <p>See README.md for the design summary and BLOG.md for the narrative.
 *
 * <p>NOTE: rename the package {@code com.yourname.idempotency} before shipping.
 */
@SpringBootApplication
@EnableScheduling
public class IdempotencyApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdempotencyApplication.class, args);
    }
}
