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

    public AnalysisSnapshotEntity() {}

    public AnalysisSnapshotEntity(String analysisId, String verdict, String confidence,
                                   int requiredSkillCount, boolean hasMissingMustHaves,
                                   Double calculatedYoe, Instant trackedAt) {
        this.analysisId = analysisId;
        this.verdict = verdict;
        this.confidence = confidence;
        this.requiredSkillCount = requiredSkillCount;
        this.hasMissingMustHaves = hasMissingMustHaves;
        this.calculatedYoe = calculatedYoe;
        this.trackedAt = trackedAt;
    }

    public String getAnalysisId() { return analysisId; }
    public String getVerdict() { return verdict; }
    public String getConfidence() { return confidence; }
    public int getRequiredSkillCount() { return requiredSkillCount; }
    public boolean isHasMissingMustHaves() { return hasMissingMustHaves; }
    public Double getCalculatedYoe() { return calculatedYoe; }
    public Instant getTrackedAt() { return trackedAt; }
}
