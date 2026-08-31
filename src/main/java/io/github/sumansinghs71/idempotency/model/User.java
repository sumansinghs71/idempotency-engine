package io.github.sumansinghs71.idempotency.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255, unique = true)
    private String email;

    @Column(name = "psp_customer_id", nullable = false, length = 50, unique = true)
    private String pspCustomerId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public User() {}

    public User(String email, String pspCustomerId) {
        this.email = email;
        this.pspCustomerId = pspCustomerId;
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getPspCustomerId() { return pspCustomerId; }
    public Instant getCreatedAt() { return createdAt; }

    public void setId(Long id) { this.id = id; }
    public void setEmail(String email) { this.email = email; }
    public void setPspCustomerId(String pspCustomerId) { this.pspCustomerId = pspCustomerId; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
