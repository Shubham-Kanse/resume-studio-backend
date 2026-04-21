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

    @Column(name = "reminder_emails_enabled")
    private Boolean reminderEmailsEnabled = true;

    @Column(name = "reminder_frequency_days")
    private Integer reminderFrequencyDays = 3;

    @Column(name = "reminder_timezone", length = 64)
    private String reminderTimezone = "UTC";

    @Column(name = "quiet_hours_enabled")
    private Boolean quietHoursEnabled = false;

    @Column(name = "quiet_hours_start")
    private Integer quietHoursStart = 22;

    @Column(name = "quiet_hours_end")
    private Integer quietHoursEnd = 8;

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
    public boolean isReminderEmailsEnabled() { return reminderEmailsEnabled == null || reminderEmailsEnabled; }
    public void setReminderEmailsEnabled(boolean reminderEmailsEnabled) { this.reminderEmailsEnabled = reminderEmailsEnabled; }
    public int getReminderFrequencyDays() { return reminderFrequencyDays == null || reminderFrequencyDays < 1 ? 3 : reminderFrequencyDays; }
    public void setReminderFrequencyDays(int reminderFrequencyDays) { this.reminderFrequencyDays = reminderFrequencyDays; }
    public String getReminderTimezone() { return reminderTimezone == null || reminderTimezone.isBlank() ? "UTC" : reminderTimezone; }
    public void setReminderTimezone(String reminderTimezone) { this.reminderTimezone = reminderTimezone; }
    public boolean isQuietHoursEnabled() { return quietHoursEnabled != null && quietHoursEnabled; }
    public void setQuietHoursEnabled(boolean quietHoursEnabled) { this.quietHoursEnabled = quietHoursEnabled; }
    public int getQuietHoursStart() { return quietHoursStart == null ? 22 : quietHoursStart; }
    public void setQuietHoursStart(int quietHoursStart) { this.quietHoursStart = quietHoursStart; }
    public int getQuietHoursEnd() { return quietHoursEnd == null ? 8 : quietHoursEnd; }
    public void setQuietHoursEnd(int quietHoursEnd) { this.quietHoursEnd = quietHoursEnd; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
