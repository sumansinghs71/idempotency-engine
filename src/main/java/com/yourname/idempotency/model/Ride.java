package com.yourname.idempotency.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "rides")
public class Ride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key_id")
    private Long idempotencyKeyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Column(name = "psp_charge_id", length = 50)
    private String pspChargeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Ride() {}

    public Long getId() { return id; }
    public Long getIdempotencyKeyId() { return idempotencyKeyId; }
    public Long getUserId() { return userId; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getPspChargeId() { return pspChargeId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setId(Long id) { this.id = id; }
    public void setIdempotencyKeyId(Long idempotencyKeyId) { this.idempotencyKeyId = idempotencyKeyId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public void setAmountCents(Long amountCents) { this.amountCents = amountCents; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setStatus(String status) { this.status = status; }
    public void setPspChargeId(String pspChargeId) { this.pspChargeId = pspChargeId; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
