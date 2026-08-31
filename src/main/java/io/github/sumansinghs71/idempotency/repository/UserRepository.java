package io.github.sumansinghs71.idempotency.repository;

import io.github.sumansinghs71.idempotency.model.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
