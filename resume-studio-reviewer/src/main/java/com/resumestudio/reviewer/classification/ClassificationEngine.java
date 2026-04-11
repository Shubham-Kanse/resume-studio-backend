package com.resumestudio.reviewer.classification;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.enums.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure deterministic decision tree.
 * Inputs: ResumeSignals
 * Output: Verdict + Confidence
 *
 * Decision rules mirror exactly what a recruiter decides in 10 seconds.
 * No scores. No weights. Binary conditions only.
 */
@Component
public class ClassificationEngine {

    private static final Logger log = LoggerFactory.getLogger(ClassificationEngine.class);

    public ClassificationResult classify(ResumeSignals signals) {
        if (signals == null) {
            log.warn("Null signals provided to classify()");
            return new ClassificationResult(Verdict.WEAK_FIT, Confidence.LOW);
        }
        Verdict verdict = computeVerdict(signals);
        Confidence confidence = computeConfidence(signals);
        return new ClassificationResult(verdict, confidence);
    }

    // ── Verdict decision tree ─────────────────────────────────────────────────

    private Verdict computeVerdict(ResumeSignals signals) {

        // ── HARD FAIL conditions → always WEAK ────────────────────────────
        // Missing must-have skills is the single most common rejection reason
        if (signals.isHasMissingMustHaves()) return Verdict.WEAK_FIT;

        // If chronology itself is unreliable, the rest of the assessment can't be trusted
        if (signals.isChronologyUnreliable()) return Verdict.WEAK_FIT;

        // YOE significantly under requirement — hard to compensate
        if (signals.getYoeFit() != null && signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT) {
            return Verdict.WEAK_FIT;
        }

        // Title is a complete mismatch AND skills don't rescue it
        if (signals.getTitleMatch() == TitleMatch.MISS
            && !signals.isAllMustHavesVisible()) return Verdict.WEAK_FIT;

        // Skills section is completely absent (NO_SECTION) AND must-haves not found
        if (signals.getSkillsFormat() == SkillsFormat.NO_SECTION
            && !signals.isAllMustHavesFound()) return Verdict.WEAK_FIT;

        // Presentation so poor a recruiter stops reading
        if (signals.isFormatWallOfText() && signals.isFormatFontTooSmall()) return Verdict.WEAK_FIT;

        // ── STRONG FIT conditions — all three must be true ─────────────────
        boolean titleOk = signals.getTitleMatch() == TitleMatch.EXACT
            || signals.getTitleMatch() == TitleMatch.ADJACENT;
        boolean yoeOk = signals.getYoeFit() != null && (signals.getYoeFit() == YoeFit.IN_RANGE
            || signals.getYoeFit() == YoeFit.OVER_RANGE);
        boolean skillsVisible = signals.isAllMustHavesFound() && signals.isAllMustHavesVisible();

        if (titleOk && yoeOk && skillsVisible) return Verdict.STRONG_FIT;

        boolean veryStrongCompany = signals.getCurrentCompanyTier() == CompanyTier.FAANG
            || signals.getCurrentCompanyTier() == CompanyTier.TIER_1;

        int conditionsMet = (titleOk ? 1 : 0) + (yoeOk ? 1 : 0) + (skillsVisible ? 1 : 0);

        // ── POSSIBLE FIT — 2 of 3 conditions, or special cases ───────────

        // All 3 met but skills are buried (found but not visible on first pass)
        if (titleOk && yoeOk && signals.isAllMustHavesFound() && signals.isHasBuriedMustHaves()) {
            return Verdict.POSSIBLE_FIT;
        }

        // 2 of 3 core conditions met
        if (conditionsMet >= 2) return Verdict.POSSIBLE_FIT;

        // Adjacent title + strong company can hold up a possible fit
        if (signals.getTitleMatch() == TitleMatch.ADJACENT
            && (veryStrongCompany || signals.getCurrentCompanyTier() == CompanyTier.SCALE_UP)
            && signals.isAllMustHavesFound()) {
            return Verdict.POSSIBLE_FIT;
        }

        // YOE slightly under but everything else good
        if (signals.getYoeFit() == YoeFit.UNDER_RANGE_MINOR && titleOk && signals.isAllMustHavesVisible()) {
            return Verdict.POSSIBLE_FIT;
        }

        // ── Default: WEAK ─────────────────────────────────────────────────
        return Verdict.WEAK_FIT;
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

    // ── Result ────────────────────────────────────────────────────────────────

    public record ClassificationResult(Verdict verdict, Confidence confidence) {}
}
