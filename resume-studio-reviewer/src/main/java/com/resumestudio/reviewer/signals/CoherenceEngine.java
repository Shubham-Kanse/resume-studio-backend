package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.ImpactLevel;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 5 — Cross-signal contradiction checks (all 6 from AI-integration.md).
 */
@Component
public class CoherenceEngine {

    public CoherenceResult check(ResumeSignals signals) {
        List<CoherenceFlag> flags = new ArrayList<>();

        // 1. SENIORITY_VS_YOE — only flag if JD actually requires seniority
        Double jdMin = signals.getJdYoeMin();
        boolean isFresherRole = jdMin == null || jdMin <= 1.0;
        if (!isFresherRole
                && signals.getCalculatedYoe() != null && signals.getCalculatedYoe() < 3.0
                && signals.getCandidateTitle() != null
                && signals.getCandidateTitle().toLowerCase().matches(".*\\b(senior|sr|lead|principal|staff)\\b.*")) {
            flags.add(new CoherenceFlag("SENIORITY_VS_YOE",
                "Title claims senior seniority but YOE is " + String.format("%.1f", signals.getCalculatedYoe()),
                ImpactLevel.MEDIUM));
        }

        // 2. LEADERSHIP_VS_EVIDENCE: summary claims leadership but no evidence
        // Skip for actual managers/directors — they ARE expected to claim leadership
        boolean isActualManager = signals.getCandidateTitle() != null
            && signals.getCandidateTitle().toLowerCase().matches(".*\\b(manager|director|head|vp|chief)\\b.*");
        if (!isActualManager && signals.isSummaryPresent() && !signals.isSummaryIsGeneric()) {
            boolean summaryClaimsLeadership = signals.getCandidateTitle() != null
                && signals.getCandidateTitle().toLowerCase().matches(".*\\b(lead|manager|head|director|vp)\\b.*");
            if (summaryClaimsLeadership && signals.getMetricDensity() < 0.1 && signals.getImpactVerbRatio() < 0.3) {
                flags.add(new CoherenceFlag("LEADERSHIP_VS_EVIDENCE",
                    "Leadership title claimed but experience bullets show little evidence of impact or team scope",
                    ImpactLevel.MEDIUM));
            }
        }

        // 3. SKILLS_INFLATION: large skills section but few appear in bullets
        // P15: threshold lowered from 20 to 12
        if (signals.getMustHaveResults() != null) {
            long foundInBullets = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MID || r.getVisibility() == SkillVisibility.BURIED)
                .count();
            long totalListed = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() != SkillVisibility.MISSING)
                .count();
            if (totalListed >= 12 && foundInBullets < 3) {
                flags.add(new CoherenceFlag("SKILLS_INFLATION",
                    "Large skills section (" + totalListed + " items) but few appear in experience bullets",
                    ImpactLevel.LOW));
            }
        }

        // 4. POSITIONING_VS_EXPERIENCE: title/summary domain vs actual experience domain
        // Proxy: title mismatch + all must-haves missing = positioning doesn't match experience
        if (signals.getTitleMatch() != null
                && signals.getTitleMatch().name().equals("MISS")
                && signals.isHasMissingMustHaves()
                && signals.getMustHaveResults() != null
                && signals.getMustHaveResults().size() >= 5) {
            long missingCount = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MISSING).count();
            if (missingCount >= signals.getMustHaveResults().size() * 0.7) {
                flags.add(new CoherenceFlag("POSITIONING_VS_EXPERIENCE",
                    "Resume positioning doesn't match the role domain — most required skills absent",
                    ImpactLevel.HIGH));
            }
        }

        // 5. TITLE_VS_BULLETS: senior title but weak bullet quality — skip for freshers and principal/manager level
        boolean isPrincipalOrManager = signals.getCandidateTitle() != null
            && signals.getCandidateTitle().toLowerCase().matches(".*\\b(principal|staff|director|manager|head|vp|chief)\\b.*");
        if (!isFresherRole && !isPrincipalOrManager
                && signals.getCandidateTitle() != null
                && signals.getCandidateTitle().toLowerCase().matches(".*\\b(senior|sr|lead)\\b.*")
                && signals.getImpactVerbRatio() < 0.3 && signals.getMetricDensity() < 0.15) {
            flags.add(new CoherenceFlag("TITLE_VS_BULLETS",
                "Senior title but bullets lack impact verbs and quantified results",
                ImpactLevel.MEDIUM));
        }

        // 6. METRIC_CREDIBILITY: suspiciously high metric density for very low YOE
        // P16: only fire if real work experience exists (not just projects)
        if (!isFresherRole && signals.getMetricDensity() > 0.9 && signals.getCalculatedYoe() != null
                && signals.getCalculatedYoe() < 1.5
                && signals.getYoeState() == com.resumestudio.reviewer.model.enums.YoeState.CALCULABLE
                && !signals.isHasProjectsSection()) {
            flags.add(new CoherenceFlag("METRIC_CREDIBILITY",
                "Very high metric density for early-career candidate — credibility check advised",
                ImpactLevel.LOW));
        }

        // 7. STALE_SKILLS: skills section contains deprecated technologies
        if (signals.isHasStaleSkills()) {
            flags.add(new CoherenceFlag("SKILL_AGE_MISMATCH",
                "Skills section includes deprecated or legacy technologies that may signal an outdated stack",
                ImpactLevel.LOW));
        }

        // 8. NLU: TITLE_VS_DEMONSTRATED_SENIORITY
        // Candidate's title claims Senior+ but bullet verbs demonstrate Junior-level work
        int demonstrated = signals.getDemonstratedSeniorityLevel();
        if (demonstrated > 0 && signals.getCandidateTitle() != null) {
            String titleLower = signals.getCandidateTitle().toLowerCase();
            boolean claimsSenior = titleLower.matches(".*\\b(senior|sr|lead|staff|principal|architect)\\b.*");
            boolean demonstratesJunior = demonstrated <= 2;
            if (claimsSenior && demonstratesJunior) {
                flags.add(new CoherenceFlag("TITLE_VS_BULLETS",
                    "Title claims senior seniority but bullet language (verb choice, scope, ownership) reflects junior-level work",
                    ImpactLevel.MEDIUM));
            }
        }

        // Coherence penalty
        double penalty = flags.stream()
            .mapToDouble(f -> switch (f.severity()) {
                case HIGH -> 0.15;
                case MEDIUM -> 0.08;
                case LOW -> 0.03;
            }).sum();

        // Transferable skill score: matched / total required (for career pivot context)
        double transferableScore = 0.0;
        String pivotType = null;
        if (signals.getMustHaveResults() != null && !signals.getMustHaveResults().isEmpty()) {
            long matched = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() != SkillVisibility.MISSING).count();
            transferableScore = (double) matched / signals.getMustHaveResults().size();
            if (transferableScore >= 0.7) pivotType = "DIRECT";
            else if (transferableScore >= 0.4) pivotType = "ADJACENT";
            else pivotType = "CAREER_CHANGE";
        }

        return new CoherenceResult(flags, penalty, transferableScore, pivotType);
    }

    public record CoherenceFlag(String type, String detail, ImpactLevel severity) {}

    public record CoherenceResult(
        List<CoherenceFlag> flags,
        double penalty,
        double transferableSkillScore,
        String pivotType
    ) {}
}
