package com.resumestudio.reviewer.model;

import java.util.List;

/**
 * ATS-style resume score.
 * Composite 0–100 score built from weighted per-signal scores.
 * Used by the ATS Score tab and Deep Dive.
 */
public class ResumeScore {

    private int composite;          // 0–100 overall
    private String grade;           // A / B / C / D / F
    private String verdict;         // "Strong", "Good", "Fair", "Weak", "Critical"

    // Per-category scores (0–100 each)
    private int skillMatchScore;
    private int presentationScore;
    private int experienceScore;
    private int tailoringScore;
    private int formatScore;

    // Per-signal breakdown
    private List<ScoreItem> breakdown;

    public static class ScoreItem {
        private String signalId;
        private String label;
        private int score;          // 0–100
        private int weight;         // relative weight (1–10)
        private int weightedScore;  // score * weight / 10
        private String tier;        // EXCELLENT / GOOD / FAIR / POOR / CRITICAL
        private String observation; // one-line explanation

        public ScoreItem() {}
        public ScoreItem(String signalId, String label, int score, int weight, String tier, String observation) {
            this.signalId = signalId;
            this.label = label;
            this.score = score;
            this.weight = weight;
            this.weightedScore = score * weight / 10;
            this.tier = tier;
            this.observation = observation;
        }

        public String getSignalId() { return signalId; }
        public String getLabel() { return label; }
        public int getScore() { return score; }
        public int getWeight() { return weight; }
        public int getWeightedScore() { return weightedScore; }
        public String getTier() { return tier; }
        public String getObservation() { return observation; }
    }

    // Getters
    public int getComposite() { return composite; }
    public void setComposite(int composite) { this.composite = composite; }
    public String getGrade() { return grade; }
    public void setGrade(String grade) { this.grade = grade; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public int getSkillMatchScore() { return skillMatchScore; }
    public void setSkillMatchScore(int skillMatchScore) { this.skillMatchScore = skillMatchScore; }
    public int getPresentationScore() { return presentationScore; }
    public void setPresentationScore(int presentationScore) { this.presentationScore = presentationScore; }
    public int getExperienceScore() { return experienceScore; }
    public void setExperienceScore(int experienceScore) { this.experienceScore = experienceScore; }
    public int getTailoringScore() { return tailoringScore; }
    public void setTailoringScore(int tailoringScore) { this.tailoringScore = tailoringScore; }
    public int getFormatScore() { return formatScore; }
    public void setFormatScore(int formatScore) { this.formatScore = formatScore; }
    public List<ScoreItem> getBreakdown() { return breakdown; }
    public void setBreakdown(List<ScoreItem> breakdown) { this.breakdown = breakdown; }
}
