package com.resumestudio.reviewer.api;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persists async review jobs in Postgres so they survive Redis eviction and
 * pod restarts. Redis is still used as the fast-path cache; DB is the durable
 * fallback and audit trail.
 *
 * job_status values: PROCESSING | DONE | ERROR
 */
@Entity
@Table(name = "review_jobs", indexes = {
    @Index(name = "idx_review_jobs_user_created", columnList = "user_id, created_at DESC"),
    @Index(name = "idx_review_jobs_status",       columnList = "job_status"),
    @Index(name = "idx_review_jobs_fetched_at",   columnList = "fetched_at")
})
public class ReviewJobEntity {

    @Id
    @Column(length = 36)
    private String id; // jobId (UUID)

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "job_status", nullable = false, length = 16)
    private String status = "PROCESSING"; // PROCESSING | DONE | ERROR

    // JSONB: Postgres stores compressed, supports indexing. Nulled after client fetches.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "JSONB")
    private String resultJson;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "outcome", length = 16)
    private String outcome; // INTERVIEW | REJECTED | NO_RESPONSE — set by user feedback

    @Column(name = "created_at", updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Set when client successfully fetches the result — result_json nulled after this. */
    @Column(name = "fetched_at")
    private Instant fetchedAt;

    public ReviewJobEntity() {}

    public ReviewJobEntity(String id, String userId) {
        this.id = id;
        this.userId = userId;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
