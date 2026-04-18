package com.resumestudio.reviewer.ats;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Full ATS score response — exact shape expected by the reference UI.
 * Maps to ATSScoreResponse in types/ats.ts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AtsScoreResponse {

    public String analysisMode = "resume-only";
    public int resumeQualityScore;
    public Integer standaloneResumeScore;
    public Integer targetRoleScore;
    public int overallScore;
    // Group scores — pre-computed on the backend so the frontend doesn't need to re-derive them
    public int impactScore;
    public int brevityScore;
    public int styleScore;
    public CategoryScores categoryScores;
    public String rating;
    public KeyFindings keyFindings;
    public List<AtsIssue> detailedIssues;
    public List<AtsRecommendation> recommendations;
    public List<AtsSectionReview> sectionReviews;
    public AtsCompatibility atsCompatibility;
    public Object keywordAnalysis = null;
    public EvidenceSummary evidenceSummary;
    public List<AtsDebugSection> debugAnalysis;
    public List<QualitySection> qualitySections; // structured — use this, not debugAnalysis
    public AdvancedInsights advancedInsights;
    // SOTA intelligence layers — always populated (JD-only fields null in resume-only mode)
    public BulletIntelligence bulletIntelligence;
    public SkillIntelligence skillIntelligence;
    public CoherenceReport coherenceReport;       // null in resume-only mode
    public SeniorityCalibration seniorityCalibration;

    // ── Nested types ──────────────────────────────────────────────────────────

    public record CategoryScores(
        Object keywordMatch,
        ScorePair formatting,
        ScorePair contentQuality,
        ScorePair professionalSummary,
        ScorePair skills,
        Object structure
    ) {}

    public record ScorePair(int score, int maxScore) {}

    public record KeyFindings(
        List<String> strengths,
        List<String> weaknesses,
        List<String> missingKeywords,
        List<String> presentKeywords
    ) {}

    public record AtsIssue(
        String severity,
        String category,
        String issue,
        String impact,
        String howToFix,
        String example
    ) {}

    public record AtsRecommendation(
        String priority,
        String action,
        String benefit,
        String implementation
    ) {}

    public record AtsSectionReview(
        String id,
        String title,
        int score,
        String status,
        String diagnosis,
        List<String> whatWorks,
        List<String> gaps,
        List<String> actions
    ) {}

    public record AtsCompatibility(
        int parseability,
        List<String> issues,
        List<String> warnings,
        List<Map<String, Object>> pageRiskMap,
        List<Map<String, Object>> flagConfidence
    ) {}

    public record EvidenceSummary(
        List<String> requiredSectionsPresent,
        List<String> missingSections,
        List<String> missingOptionalSections,
        List<String> matchedKeywords,
        List<String> missingKeywords,
        Integer yearsRequired,
        Integer yearsEstimated,
        Boolean meetsYearsRequirement,
        String degreeRequirement,
        Boolean meetsDegreeRequirement,
        List<String> requiredCertifications,
        List<String> missingCertifications,
        List<String> matchedRoleFamilies,
        String expectedSeniority,
        String observedSeniority,
        Boolean seniorityAligned,
        boolean managementRequired,
        boolean managementObserved,
        List<String> parseabilityIssues,
        List<String> parseabilityWarnings,
        Object keywordCoverageBySection,
        AdvancedSignals advancedSignals
    ) {}

    public record AdvancedSignals(
        TimelineSignals timeline,
        WritingSignals writing,
        CredibilitySignals credibility
    ) {}

    public record TimelineSignals(int overlaps, int significantGaps, Integer explainedGaps) {}
    public record WritingSignals(int tenseIssues, int terminologyDrift, Integer grammarIssues) {}
    public record CredibilitySignals(int uncoveredCoreSkills, int bulletEvidenceScore, int weakMetrics, int strongMetrics) {}

    public record AtsDebugSection(
        String id,
        String title,
        String summary,
        List<AtsDebugItem> items
    ) {}

    public record AtsDebugItem(
        String label,
        String detail,
        String suggestion,
        String severity
    ) {}

    // ── Quality sections — structured per-section scoring (replaces debugAnalysis parsing) ──
    public record QualitySection(
        String id,           // e.g. "quantifying-impact"
        String label,        // e.g. "Quantifying Impact"
        int score,           // 0-100
        String severity,     // "good" | "info" | "warning" | "critical"
        List<String> issues, // individual issue strings, already unescaped
        String suggestion,   // single actionable fix
        // bullets-group extras
        Integer quantifiedBullets,
        Integer totalBullets,
        String averageBulletLength,
        // NLG fields — all text the frontend previously hardcoded
        String recruiterTip,           // observation sentence from resume_quality_ontology.json
        String beforeExample,          // first real bullet from issues (nullable)
        List<String> foundItems        // found fillers / buzzwords / weak verbs actually in this resume
    ) {}

    public record AdvancedInsights(
        ConfidenceInsight confidence,
        BenchmarkInsight benchmark,
        List<Counterfactual> counterfactuals,
        List<EvidenceGraphEntry> evidenceGraph
    ) {}

    public record EvidenceGraphEntry(
        String skill,
        List<String> evidenceBullets,
        List<String> matchedResponsibilities
    ) {}

    public record ConfidenceInsight(
        int score,
        UncertaintyBand uncertaintyBand,
        List<ConfidenceContributor> contributors
    ) {}

    public record UncertaintyBand(int lower, int upper, int delta) {}

    public record ConfidenceContributor(String label, String impact, String detail) {}

    public record BenchmarkInsight(
        String mode,
        int percentile,
        String cohort,
        List<String> comparedAgainst
    ) {}

    public record Counterfactual(String action, int estimatedScoreLift, String rationale, String priority) {}

    // ── SOTA intelligence layers ──────────────────────────────────────────────

    public record BulletIntelligence(
        List<String> topBullets,              // top 5 bullets by composite NLP score
        List<EnrichedBulletData> bullets,     // per-bullet enrichment (no embedding)
        int duplicateCount,
        int credibilityFlagCount
    ) {}

    public record EnrichedBulletData(
        String text,
        String roleTitle,
        String company,
        boolean metricDetected,
        String verbQuality,        // STRONG / MEDIUM / WEAK / MISSING
        String impactDirection,    // IMPROVEMENT / PREVENTION / SCALE / AMBIGUOUS
        String scopeSignal,        // INDIVIDUAL / TEAM / ORG
        int specificityScore,      // 0–10
        boolean credibilityFlag,
        boolean duplicateFlag
    ) {}

    public record SkillIntelligence(
        int credibilityScore,             // 0–100: how well bullets evidence claimed skills
        boolean hasUnevidencedSkills,
        List<String> shallowSkills,       // evidenced in only 1 bullet — thin coverage
        List<String> impliedSkills,       // skills implied by ontology but not explicitly listed
        int keywordDensityScore           // 0–100: fraction of required skills appearing 3+ times
    ) {}

    public record CoherenceReport(
        List<CoherenceFlagData> flags,
        int penaltyPct,                   // 0–100 coherence penalty
        int transferableSkillScore,       // 0–100
        String pivotType                  // DIRECT / ADJACENT / CAREER_CHANGE
    ) {}

    public record CoherenceFlagData(
        String type,
        String detail,
        String severity                   // HIGH / MEDIUM / LOW
    ) {}

    public record SeniorityCalibration(
        String claimedTitle,
        int demonstratedLevel,            // 0 = unknown, 1 = intern … 6 = principal/staff
        String demonstratedLabel,         // "Junior" / "Mid" / "Senior" / "Staff+" / "Unknown"
        boolean aligned                   // claimed seniority matches demonstrated level
    ) {}
}
