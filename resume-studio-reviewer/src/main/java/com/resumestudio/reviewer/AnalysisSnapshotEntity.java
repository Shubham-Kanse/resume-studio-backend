package com.resumestudio.reviewer;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "analysis_snapshots")
public class AnalysisSnapshotEntity {

    @Id
    private String analysisId;
    private String verdict;
    private String confidence;
    private int requiredSkillCount;
    private boolean hasMissingMustHaves;
    private Double calculatedYoe;
    private Instant trackedAt;

    // Richer fields for feedback loop / model training
    private String userId;
    private String roleTitle;
    private String roleDomain;
    private Integer compositeScore;
    private Integer skillMatchScore;
    private String jdHash;       // SHA-256 of JD text — for deduplication
    private String resumeHash;   // SHA-256 of resume bytes — for deduplication
    private Boolean userAccepted; // outcome label — set later via feedback API (null = unknown)

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
