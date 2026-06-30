package com.yourname.idempotency;

import com.yourname.idempotency.model.User;
import com.yourname.idempotency.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spin up a real Postgres for the integration + chaos tests.
 *
 * <p>Container is static so it's reused across the suite — Testcontainers
 * shuts it down on JVM exit. Each test class is expected to clean its own
 * rows in {@code @BeforeEach}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractPostgresIT {

    @Container
    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("idempotency")
                    .withUsername("idem")
                    .withPassword("idem")
                    .withReuse(false);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected UserRepository userRepo;

    protected long seedUser(String email) {
        return userRepo.save(new User(email, "cus_" + email.hashCode())).getId();
    }

    @BeforeEach
    void resetAll() {
        // Tests are responsible for further per-class cleanup via the
        // repositories they touch.
    }
}
