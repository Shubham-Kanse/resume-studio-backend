package com.resumestudio.reviewer;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "analysis_snapshots", indexes = {
    @Index(name = "idx_snapshots_user_id",       columnList = "user_id"),
    @Index(name = "idx_snapshots_tracked_at",    columnList = "tracked_at"),
    @Index(name = "idx_snapshots_jd_resume_hash", columnList = "jd_hash, resume_hash")
})
public class AnalysisSnapshotEntity {

    @Id
    @Column(length = 36)
    private String analysisId;

    @Column(length = 16)
    private String verdict;

    @Column(length = 8)
    private String confidence;

    private int requiredSkillCount;
    private boolean hasMissingMustHaves;
    private Double calculatedYoe;

    @Column(name = "tracked_at")
    private Instant trackedAt;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "role_title", length = 255)
    private String roleTitle;

    @Column(name = "role_domain", length = 128)
    private String roleDomain;

    @Column(name = "composite_score")
    private Integer compositeScore;

    @Column(name = "skill_match_score")
    private Integer skillMatchScore;

    /** SHA-256 hex of JD text — 64 chars exactly */
    @Column(name = "jd_hash", length = 64)
    private String jdHash;

    /** SHA-256 hex of resume bytes — 64 chars exactly */
    @Column(name = "resume_hash", length = 64)
    private String resumeHash;

    /** Outcome label set later via feedback API (null = unknown) */
    @Column(name = "user_accepted")
    private Boolean userAccepted;

    public AnalysisSnapshotEntity() {}

    public AnalysisSnapshotEntity(String analysisId, String verdict, String confidence,
                                   int requiredSkillCount, boolean hasMissingMustHaves,
                                   Double calculatedYoe, Instant trackedAt,
                                   String userId, String roleTitle, String roleDomain,
                                   Integer compositeScore, Integer skillMatchScore,
                                   String jdHash, String resumeHash) {
        this.analysisId = analysisId;
        this.verdict = verdict;
        this.confidence = confidence;
        this.requiredSkillCount = requiredSkillCount;
        this.hasMissingMustHaves = hasMissingMustHaves;
        this.calculatedYoe = calculatedYoe;
        this.trackedAt = trackedAt;
        this.userId = userId;
        this.roleTitle = roleTitle;
        this.roleDomain = roleDomain;
        this.compositeScore = compositeScore;
        this.skillMatchScore = skillMatchScore;
        this.jdHash = jdHash;
        this.resumeHash = resumeHash;
    }

    public String getAnalysisId() { return analysisId; }
    public String getVerdict() { return verdict; }
    public String getConfidence() { return confidence; }
    public int getRequiredSkillCount() { return requiredSkillCount; }
    public boolean isHasMissingMustHaves() { return hasMissingMustHaves; }
    public Double getCalculatedYoe() { return calculatedYoe; }
    public Instant getTrackedAt() { return trackedAt; }
    public String getUserId() { return userId; }
    public String getRoleTitle() { return roleTitle; }
    public String getRoleDomain() { return roleDomain; }
    public Integer getCompositeScore() { return compositeScore; }
    public Integer getSkillMatchScore() { return skillMatchScore; }
    public String getJdHash() { return jdHash; }
    public String getResumeHash() { return resumeHash; }
    public Boolean getUserAccepted() { return userAccepted; }
    public void setUserAccepted(Boolean userAccepted) { this.userAccepted = userAccepted; }
}
