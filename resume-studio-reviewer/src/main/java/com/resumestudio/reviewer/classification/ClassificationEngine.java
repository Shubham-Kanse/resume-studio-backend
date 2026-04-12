package com.resumestudio.reviewer.classification;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.signals.CoherenceEngine;
import com.resumestudio.reviewer.signals.CoherenceEngine.CoherenceResult;
import com.resumestudio.reviewer.model.enums.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure deterministic weighted scoring engine per AI-integration.md Layer 6.
 * Inputs: ResumeSignals + CoherenceResult
 * Output: ClassificationResult with verdict, confidence, and all metadata fields.
 */
@Component
public class ClassificationEngine {

    private static final Logger log = LoggerFactory.getLogger(ClassificationEngine.class);

    public ClassificationResult classify(ResumeSignals signals) {
        return classify(signals, null);
    }

    public ClassificationResult classify(ResumeSignals signals, CoherenceResult coherence) {
        if (signals == null) {
            log.warn("Null signals provided to classify()");
            return new ClassificationResult(Verdict.WEAK_FIT, Confidence.LOW,
                InterviewLikelihood.UNLIKELY, 15, SeniorityCalibration.UNCLEAR,
                5, JdClarity.MEDIUM, RecruiterType.UNKNOWN, CompetitiveContext.UNKNOWN);
        }
        Verdict verdict = computeVerdict(signals, coherence);
        Confidence confidence = computeConfidence(signals);
        JdClarity jdClarity = signals.getJdClarity() != null ? signals.getJdClarity() : JdClarity.MEDIUM;
        return new ClassificationResult(
            verdict,
            confidence,
            computeInterviewLikelihood(verdict, confidence),
            computeScanDuration(verdict),
            computeSeniorityCalibration(signals),
            capTailoringScore(computeTailoringScore(signals), verdict),
            jdClarity,
            inferRecruiterType(signals),
            inferCompetitiveContext(signals)
        );
    }

    // ── Verdict decision tree ─────────────────────────────────────────────────

    private Verdict computeVerdict(ResumeSignals signals, CoherenceResult coherence) {

        // Hard disqualifiers — override the score
        if (signals.isChronologyUnreliable()) return Verdict.WEAK_FIT;
        if (signals.isFormatWallOfText() && signals.isFormatFontTooSmall()) return Verdict.WEAK_FIT;
        // Significant YOE gap is a hard disqualifier — no amount of skill coverage compensates
        if (signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT) return Verdict.WEAK_FIT;

        // Weighted signal score per AI-integration.md Layer 6
        double titleScore = switch (signals.getTitleMatch() == null ? TitleMatch.MISS : signals.getTitleMatch()) {
            case EXACT -> 1.0;
            case ADJACENT -> 0.75;
            case RELATED -> 0.4;
            case MISS -> 0.0;
            default -> 0.2;
        };

        double skillsScore;
        if (signals.isAllMustHavesVisible()) skillsScore = 1.0;
        else if (signals.isAllMustHavesFound()) skillsScore = 0.7;
        else if (signals.isHasBuriedMustHaves()) skillsScore = 0.5;
        else if (signals.isHasMissingMustHaves()) {
            long total = signals.getMustHaveResults() == null ? 1 : signals.getMustHaveResults().size();
            long missing = signals.getMustHaveResults() == null ? 1 :
                signals.getMustHaveResults().stream()
                    .filter(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING)
                    .count();
            skillsScore = Math.max(0.0, 1.0 - (double) missing / total);
        } else skillsScore = 0.5;

        double currentRoleScore = switch (signals.getTitleMatch() == null ? TitleMatch.MISS : signals.getTitleMatch()) {
            case EXACT, ADJACENT -> 1.0;
            case RELATED -> 0.5;
            default -> 0.2;
        };

        double impactScore;
        double ivr = signals.getImpactVerbRatio();
        double md = signals.getMetricDensity();
        if (ivr >= 0.7 && md >= 0.4) impactScore = 1.0;
        else if (ivr >= 0.5 || md >= 0.3) impactScore = 0.6;
        else if (ivr > 0 || md > 0) impactScore = 0.3;
        else impactScore = 0.5; // no bullet data — neutral

        double domainScore = 0.5; // default — no domain signal yet (O*NET stub)

        double yoeScore = switch (signals.getYoeFit() == null ? YoeFit.CANNOT_DETERMINE : signals.getYoeFit()) {
            case IN_RANGE -> 1.0;
            case OVER_RANGE -> 0.8;
            case UNDER_RANGE_MINOR -> 0.5;
            case UNDER_RANGE_SIGNIFICANT -> 0.1;
            case CANNOT_DETERMINE -> {
                // If JD has no YOE requirement, treat as neutral
                // If JD requires 0-2 years (fresher role), treat as neutral too
                Double jdMin = signals.getJdYoeMin();
                Double jdMax = signals.getJdYoeMax();
                if (jdMin == null) yield 0.7; // no requirement — neutral-positive
                if (jdMin <= 0 && (jdMax == null || jdMax <= 2)) yield 0.7; // fresher role — neutral
                yield 0.4; // unknown YOE for a role with real requirements
            }
        };

        double signalScore =
            titleScore    * 0.15 +
            skillsScore   * 0.25 +
            currentRoleScore * 0.20 +
            impactScore   * 0.15 +
            domainScore   * 0.15 +
            yoeScore      * 0.10;

        // jdClarity=LOW caps signal score
        if (signals.getJdClarity() == JdClarity.LOW) {
            signalScore = Math.min(signalScore, 0.60);
        }

        double coherencePenalty = coherence != null ? coherence.penalty() : 0.0;
        double finalScore = signalScore - coherencePenalty;

        // Hard cap: majority of must-haves missing → WEAK_FIT regardless of other signals
        if (signals.isHasMissingMustHaves() && signals.getMustHaveResults() != null) {
            long total = signals.getMustHaveResults().size();
            long missing = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING)
                .count();
            double missingRatio = total > 0 ? (double) missing / total : 0;
            if (missingRatio > 0.5) return Verdict.WEAK_FIT;
            if (missingRatio > 0.25 && finalScore >= 0.75) finalScore = 0.65; // cap at POSSIBLE
        }

        Verdict verdict;
        if (finalScore >= 0.75) verdict = Verdict.STRONG_FIT;
        else if (finalScore >= 0.55) verdict = Verdict.POSSIBLE_FIT;
        else if (finalScore >= 0.35) verdict = Verdict.WEAK_FIT;
        else verdict = Verdict.NO_FIT;

        // Minor YOE gap caps at POSSIBLE_FIT — can't be STRONG even with perfect skills
        if (verdict == Verdict.STRONG_FIT && signals.getYoeFit() == YoeFit.UNDER_RANGE_MINOR) {
            verdict = Verdict.POSSIBLE_FIT;
        }

        return verdict;
    }

    // ── Confidence computation ────────────────────────────────────────────────

    private Confidence computeConfidence(ResumeSignals signals) {
        int penaltyPoints = 0;

        // Parse quality issues
        if (signals.getYoeState() != null && signals.getYoeState() == YoeState.MISSING) penaltyPoints += 2;
        if (signals.getYoeState() != null && signals.getYoeState() == YoeState.PARTIAL) penaltyPoints += 1;
        if (signals.getSkillsFormat() != null && signals.getSkillsFormat() == SkillsFormat.NO_SECTION) penaltyPoints += 1;
        if (signals.getTitleMatch() == null) penaltyPoints += 2;

        // Ambiguous content
        if (signals.isHasBuriedMustHaves()) penaltyPoints += 1;
        if (signals.getYoeState() != null && signals.getYoeState() == YoeState.VAGUE) penaltyPoints += 1;
        if (signals.isHasChronologyIssues()) penaltyPoints += 1;
        if (signals.isChronologyUnreliable()) penaltyPoints += 2;
        
        // Bullet quality - weak storytelling reduces confidence
        if (signals.getImpactVerbRatio() < 0.3 || signals.getMetricDensity() < 0.1) {
            penaltyPoints += 1;
        }

        return switch (penaltyPoints) {
            case 0, 1 -> Confidence.HIGH;
            case 2, 3 -> Confidence.MEDIUM;
            default -> Confidence.LOW;
        };
    }

    private InterviewLikelihood computeInterviewLikelihood(Verdict verdict, Confidence confidence) {
        return switch (verdict) {
            case STRONG_FIT -> confidence == Confidence.HIGH ? InterviewLikelihood.VERY_LIKELY : InterviewLikelihood.LIKELY;
            case POSSIBLE_FIT -> confidence == Confidence.HIGH ? InterviewLikelihood.LIKELY : InterviewLikelihood.POSSIBLE;
            case WEAK_FIT -> InterviewLikelihood.UNLIKELY;
            case NO_FIT -> InterviewLikelihood.VERY_UNLIKELY;
        };
    }

    private int computeScanDuration(Verdict verdict) {
        return switch (verdict) {
            case NO_FIT -> 8;
            case WEAK_FIT -> 15;
            case POSSIBLE_FIT -> 30;
            case STRONG_FIT -> 60;
        };
    }

    private SeniorityCalibration computeSeniorityCalibration(ResumeSignals signals) {
        if (signals.getYoeFit() == YoeFit.OVER_RANGE) return SeniorityCalibration.OVER_QUALIFIED;
        if (signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT) return SeniorityCalibration.REACHING_UP;
        if (signals.getYoeFit() == YoeFit.IN_RANGE) return SeniorityCalibration.MATCHED;
        if (signals.getYoeFit() == YoeFit.UNDER_RANGE_MINOR) return SeniorityCalibration.REACHING_UP;
        // CANNOT_DETERMINE: if JD is a fresher role, treat as MATCHED
        Double jdMin = signals.getJdYoeMin();
        Double jdMax = signals.getJdYoeMax();
        if (jdMin == null || (jdMin <= 0 && (jdMax == null || jdMax <= 2))) return SeniorityCalibration.MATCHED;
        return SeniorityCalibration.UNCLEAR;
    }

    private int computeTailoringScore(ResumeSignals signals) {
        int score = 5; // baseline
        if (signals.getTitleMatch() == TitleMatch.EXACT) score += 2;
        else if (signals.getTitleMatch() == TitleMatch.ADJACENT) score += 1;
        if (signals.isAllMustHavesVisible()) score += 2;
        else if (signals.isAllMustHavesFound()) score += 1;
        if (signals.isSummaryMentionsTitle()) score += 1;
        return Math.min(10, score);
    }

    /** Cap tailoringScore to be consistent with verdict. */
    private int capTailoringScore(int score, Verdict verdict) {
        return switch (verdict) {
            case NO_FIT -> Math.min(score, 3);
            case WEAK_FIT -> Math.min(score, 5);
            case POSSIBLE_FIT -> Math.min(score, 7);
            case STRONG_FIT -> score;
        };
    }

    /** Heuristic recruiter type from JD title and company tier. */
    private RecruiterType inferRecruiterType(ResumeSignals signals) {
        String title = signals.getJdTitle();
        if (title == null) return RecruiterType.UNKNOWN;
        String lower = title.toLowerCase();
        if (lower.contains("senior") || lower.contains("staff") || lower.contains("principal")
                || lower.contains("architect") || lower.contains("engineer")) {
            return RecruiterType.TECHNICAL_RECRUITER;
        }
        if (lower.contains("manager") || lower.contains("director") || lower.contains("head")) {
            return RecruiterType.HIRING_MANAGER;
        }
        return RecruiterType.HR_GENERALIST;
    }

    /** Heuristic competitive context from required skill count and JD clarity. */
    private CompetitiveContext inferCompetitiveContext(ResumeSignals signals) {
        int skillCount = signals.getMustHaveResults() != null ? signals.getMustHaveResults().size() : 0;
        if (skillCount >= 10) return CompetitiveContext.HIGHLY_COMPETITIVE;
        if (skillCount >= 5) return CompetitiveContext.MODERATE;
        if (skillCount > 0) return CompetitiveContext.NICHE;
        return CompetitiveContext.UNKNOWN;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    public record ClassificationResult(
        Verdict verdict,
        Confidence confidence,
        InterviewLikelihood interviewLikelihood,
        int scanDuration,
        SeniorityCalibration seniorityCalibration,
        int tailoringScore,
        JdClarity jdClarity,
        RecruiterType recruiterType,
        CompetitiveContext competitiveContext
    ) {}
}
