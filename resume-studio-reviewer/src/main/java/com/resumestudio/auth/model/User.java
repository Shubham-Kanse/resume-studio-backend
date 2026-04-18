package com.resumestudio.auth.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_stripe_customer_id", columnList = "stripe_customer_id")
})
public class User {

    @Id
    private String id; // Supabase user UUID

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Plan plan = Plan.FREE;

    @Column(name = "stripe_customer_id", length = 32)
    private String stripeCustomerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    public void prePersist() { this.updatedAt = Instant.now(); }

    @PreUpdate
    public void preUpdate() { this.updatedAt = Instant.now(); }

    public User() {}

    public User(String id) { this.id = id; }

    public String getId() { return id; }
    public Plan getPlan() { return plan; }
    public void setPlan(Plan plan) { this.plan = plan; }
    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
