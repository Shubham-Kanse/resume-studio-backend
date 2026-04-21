package com.resumestudio.tracker;

import jakarta.persistence.*;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_applications", indexes = {
    @Index(name = "idx_job_applications_user_created", columnList = "user_id, created_at DESC"),
    @Index(name = "idx_job_applications_due_reminder", columnList = "reminder_enabled, next_reminder_at")
})
public class JobApplication {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(name = "user_id", nullable = false, length = 36)
    @jakarta.validation.constraints.NotNull
    private String userId;

    @Column(nullable = false, length = 20)
    private String stage = "To Apply";

    @Size(max = 255)
    private String company;

    @Size(max = 255)
    private String position;

    @Column(name = "job_url", columnDefinition = "TEXT")
    private String jobUrl;

    @Column(name = "resume_s3_key")
    private String resumeS3Key;

    @Column(name = "resume_name")
    private String resumeName;

    @Column(name = "user_email", length = 320)
    private String userEmail;

    @Column(name = "date_applied")
    private LocalDate dateApplied;

    @Column(columnDefinition = "TEXT")
    @Size(max = 5000)
    private String notes;

    @Column(name = "reminder_enabled")
    private Boolean reminderEnabled = true;

    @Column(name = "reminder_frequency_days")
    private Integer reminderFrequencyDays = 3;

    @Column(name = "next_reminder_at")
    private Instant nextReminderAt;

    @Column(name = "last_reminder_sent_at")
    private Instant lastReminderSentAt;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    public void prePersist() {
        this.updatedAt = Instant.now();
        if (this.reminderEnabled == null) this.reminderEnabled = true;
        if (this.reminderFrequencyDays == null || this.reminderFrequencyDays < 1) this.reminderFrequencyDays = 3;
    }

    @PreUpdate
    public void preUpdate() { this.updatedAt = Instant.now(); }

    public JobApplication() {}

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public String getJobUrl() { return jobUrl; }
    public void setJobUrl(String jobUrl) { this.jobUrl = jobUrl; }
    public String getResumeS3Key() { return resumeS3Key; }
    public void setResumeS3Key(String resumeS3Key) { this.resumeS3Key = resumeS3Key; }
    public String getResumeName() { return resumeName; }
    public void setResumeName(String resumeName) { this.resumeName = resumeName; }
    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public LocalDate getDateApplied() { return dateApplied; }
    public void setDateApplied(LocalDate dateApplied) { this.dateApplied = dateApplied; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isReminderEnabled() { return reminderEnabled == null || reminderEnabled; }
    public void setReminderEnabled(boolean reminderEnabled) { this.reminderEnabled = reminderEnabled; }
    public int getReminderFrequencyDays() { return reminderFrequencyDays == null || reminderFrequencyDays < 1 ? 3 : reminderFrequencyDays; }
    public void setReminderFrequencyDays(int reminderFrequencyDays) { this.reminderFrequencyDays = reminderFrequencyDays; }
    public Instant getNextReminderAt() { return nextReminderAt; }
    public void setNextReminderAt(Instant nextReminderAt) { this.nextReminderAt = nextReminderAt; }
    public Instant getLastReminderSentAt() { return lastReminderSentAt; }
    public void setLastReminderSentAt(Instant lastReminderSentAt) { this.lastReminderSentAt = lastReminderSentAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
