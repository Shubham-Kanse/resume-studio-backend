package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orchestrates all feedback generation.
 * Inputs:  ResumeSignals + verdict
 * Outputs: List<Signal>, List<Fix>, summary paragraph
 *
 * Every Signal and Fix traces to a specific signal enum value.
 * Every Fix has an observation, interpretation, action, and impact level.
 * Fixes are ordered by impact (HIGH first).
 */
@Component
public class FeedbackGenerator {

    private final SentenceBank bank;

    public FeedbackGenerator(SentenceBank bank) {
        this.bank = bank;
    }

    public FeedbackOutput generate(ResumeSignals signals, Verdict verdict) {
        List<Signal> signalList = buildSignals(signals);
        List<Fix> fixes = buildFixes(signals);
        String summary = buildSummary(signals, verdict, fixes);

        return new FeedbackOutput(signalList, fixes, summary);
    }

    // ── Signal list (max 6 for UI 2×3 grid) ──────────────────────────────────

    private List<Signal> buildSignals(ResumeSignals signals) {
        List<Signal> list = new ArrayList<>();

        // 1. Title match
        SignalStatus titleStatus = switch (signals.getTitleMatch()) {
            case EXACT, ADJACENT -> SignalStatus.PASS;
            case RELATED -> SignalStatus.WARN;
            case MISS -> SignalStatus.FAIL;
            default -> SignalStatus.WARN;
        };
        list.add(new Signal("title_match", "Title match", titleStatus,
            SignalFriction.NONE,
            bank.titleObservation(signals),
            bank.titleInterpretation(signals),
            ImpactLevel.HIGH));

        // 2. YOE fit
        SignalStatus yoeStatus = switch (signals.getYoeFit()) {
            case IN_RANGE -> SignalStatus.PASS;
            case UNDER_RANGE_MINOR, OVER_RANGE -> SignalStatus.WARN;
            case UNDER_RANGE_SIGNIFICANT, CANNOT_DETERMINE -> SignalStatus.FAIL;
        };
        SignalFriction yoeFriction = switch (signals.getYoeState()) {
            case EXPLICIT -> SignalFriction.NONE;
            case CALCULABLE -> SignalFriction.LOW;
            case PARTIAL, INCONSISTENT_FORMAT -> SignalFriction.MEDIUM;
            default -> SignalFriction.HIGH;
        };
        list.add(new Signal("yoe_fit", "Years of experience", yoeStatus, yoeFriction,
            bank.yoeObservation(signals), bank.yoeInterpretation(signals), ImpactLevel.HIGH));

        // 3. Must-have skills visible
        SignalStatus skillsStatus;
        SignalFriction skillsFriction;
        if (signals.isHasMissingMustHaves()) {
            skillsStatus = SignalStatus.FAIL; skillsFriction = SignalFriction.HIGH;
        } else if (signals.isHasBuriedMustHaves()) {
            skillsStatus = SignalStatus.WARN; skillsFriction = SignalFriction.MEDIUM;
        } else {
            skillsStatus = SignalStatus.PASS; skillsFriction = SignalFriction.NONE;
        }
        list.add(new Signal("must_haves_visible", "Must-have skills visible",
            skillsStatus, skillsFriction,
            bank.skillsFormatObservation(signals),
            bank.skillsFormatInterpretation(signals),
            ImpactLevel.HIGH));

        // 4. Company context
        SignalStatus companyStatus = switch (signals.getCurrentCompanyTier()) {
            case FAANG, TIER_1 -> SignalStatus.PASS;
            case SCALE_UP, DESCRIBED -> SignalStatus.PASS;
            case STARTUP -> SignalStatus.WARN;
            case UNKNOWN -> SignalStatus.WARN;
        };
        list.add(new Signal("company_context", "Company context", companyStatus, SignalFriction.NONE,
            bank.companyObservation(signals), bank.companyInterpretation(signals), ImpactLevel.MEDIUM));

        // 5. Summary present and quality
        SignalStatus summaryStatus;
        if (!signals.isSummaryPresent()) summaryStatus = SignalStatus.WARN;
        else if (signals.isSummaryIsGeneric()) summaryStatus = SignalStatus.WARN;
        else if (signals.isSummaryMentionsYoe() && signals.isSummaryMentionsSkills()) summaryStatus = SignalStatus.PASS;
        else summaryStatus = SignalStatus.WARN;
        list.add(new Signal("summary_quality", "Summary section", summaryStatus, SignalFriction.NONE,
            bank.summaryObservation(signals), bank.summaryInterpretation(signals), ImpactLevel.MEDIUM));

        // 6. Title progression / format (pick most relevant)
        if (signals.isFormatWallOfText() || signals.isFormatHasPhoto() || signals.isFormatTooManyPages()) {
            SignalStatus formatStatus = SignalStatus.WARN;
            list.add(new Signal("format_quality", "Layout & formatting", formatStatus, SignalFriction.MEDIUM,
                "Formatting issues detected that increase scan friction.",
                "Layout problems make the document harder to read at speed.",
                ImpactLevel.MEDIUM));
        } else {
            TitleProgression prog = signals.getTitleProgression();
            SignalStatus progressionStatus = prog == TitleProgression.GROWING ? SignalStatus.PASS
                : prog == TitleProgression.FLAT ? SignalStatus.WARN : SignalStatus.WARN;
            String progObs = prog == TitleProgression.GROWING ? "Your career shows an upward title trajectory."
                : prog == TitleProgression.FLAT ? "Your title has remained at the same level across roles."
                : "Career trajectory could not be determined from available information.";
            list.add(new Signal("title_progression", "Career trajectory", progressionStatus, SignalFriction.NONE,
                progObs, "Recruiters look for growth. An upward trajectory builds confidence.", ImpactLevel.LOW));
        }

        return list;
    }

    // ── Fix list ──────────────────────────────────────────────────────────────

    private List<Fix> buildFixes(ResumeSignals signals) {
        List<Fix> fixes = new ArrayList<>();
        int rank = 1;

        // Missing must-haves — always rank 1 if present
        if (signals.isHasMissingMustHaves()) {
            List<SkillMatchResult> missing = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MISSING).toList();
            if (!missing.isEmpty()) {
                String skillList = missing.stream().map(SkillMatchResult::getJdSkill)
                    .reduce((a, b) -> a + ", " + b).orElse("required skills");
                fixes.add(new Fix(rank++, "must_haves_visible",
                    "Add these missing must-have skills: " + skillList,
                    "These are the primary technical requirements for this role. Their absence is typically a decisive rejection signal.",
                    "Only add skills you genuinely have. If these are gaps, consider targeting roles that match your current stack.",
                    ImpactLevel.HIGH));
            }
        }

        // Buried must-haves
        if (signals.isHasBuriedMustHaves()) {
            List<SkillMatchResult> buried = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.BURIED || r.getVisibility() == SkillVisibility.MID).toList();
            if (!buried.isEmpty()) {
                String skillList = buried.stream().map(SkillMatchResult::getJdSkill)
                    .reduce((a, b) -> a + ", " + b).orElse("key skills");
                fixes.add(new Fix(rank++, "must_haves_visible",
                    "Move these skills to your skills section: " + skillList,
                    bank.skillVisibilityInterpretation(buried.get(0)),
                    "Add them to your skills section. Example: 'Programming: " + buried.get(0).getJdSkill() + ", ...'",
                    ImpactLevel.HIGH));
            }
        }

        // Summary missing or weak
        String summaryAction = bank.summaryAction(signals);
        if (summaryAction != null) {
            fixes.add(new Fix(rank++, "summary_quality",
                !signals.isSummaryPresent() ? "Add a professional summary at the top of your resume" : "Rewrite your summary with technical specifics",
                bank.summaryInterpretation(signals),
                summaryAction,
                ImpactLevel.HIGH));
        }

        // Skills format
        String formatAction = bank.skillsFormatAction(signals);
        if (formatAction != null) {
            fixes.add(new Fix(rank++, "must_haves_visible",
                "Restructure your skills section",
                bank.skillsFormatInterpretation(signals),
                formatAction,
                ImpactLevel.MEDIUM));
        }

        // Company no context
        String companyAction = bank.companyAction(signals);
        if (companyAction != null) {
            fixes.add(new Fix(rank++, "company_context",
                "Add a descriptor to your current company",
                bank.companyInterpretation(signals),
                companyAction,
                ImpactLevel.MEDIUM));
        }

        // Title mismatch
        String titleAction = bank.titleAction(signals);
        if (titleAction != null) {
            fixes.add(new Fix(rank++, "title_match",
                "Bridge the title gap in your summary",
                bank.titleInterpretation(signals),
                titleAction,
                ImpactLevel.MEDIUM));
        }

        // YOE issues
        String yoeAction = bank.yoeAction(signals);
        if (yoeAction != null) {
            fixes.add(new Fix(rank++, "yoe_fit",
                "Clarify your years of experience",
                bank.yoeInterpretation(signals),
                yoeAction,
                ImpactLevel.signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT ? ImpactLevel.HIGH : ImpactLevel.MEDIUM));
        }

        // Unexplained gap
        if (signals.isHasUnexplainedGap()) {
            fixes.add(new Fix(rank++, "yoe_fit",
                "Label your career gap",
                bank.gapObservation(signals),
                bank.gapAction(),
                ImpactLevel.MEDIUM));
        }

        // Filename
        if (!signals.isFilenameProfessional()) {
            fixes.add(new Fix(rank++, "filename",
                "Rename your resume file",
                bank.filenameInterpretation(signals),
                bank.filenameAction(),
                ImpactLevel.LOW));
        }

        // Formatting issues
        if (signals.isFormatHasPhoto()) {
            fixes.add(new Fix(rank++, "format_quality",
                "Remove the photo from your resume",
                "Photos are non-standard for tech roles in most markets and introduce unconscious bias risk. Most ATS systems strip them anyway.",
                "Remove the photo. Your work speaks for itself.",
                ImpactLevel.LOW));
        }

        // Skill age mismatch
        if (signals.isHasSkillAgeMismatch()) {
            fixes.add(new Fix(rank++, "skill_age_mismatch",
                "Correct your stated years of experience for a skill",
                "An impossible years-of-experience claim undermines credibility immediately.",
                bank.skillAgeMismatchAction(signals),
                ImpactLevel.HIGH));
        }

        // Sort by impact
        fixes.sort(Comparator.comparingInt(f -> impactOrder(f.getImpact())));
        for (int i = 0; i < fixes.size(); i++) fixes.get(i).setRank(i + 1);

        return fixes;
    }

    // ── Summary paragraph ─────────────────────────────────────────────────────

    private String buildSummary(ResumeSignals signals, Verdict verdict, List<Fix> fixes) {
        StringBuilder sb = new StringBuilder();

        // Lead with verdict driver
        switch (verdict) {
            case STRONG_FIT -> sb.append("The foundation is solid — title, experience, and key skills all align. ");
            case POSSIBLE_FIT -> {
                if (signals.isHasBuriedMustHaves() && !signals.isHasMissingMustHaves()) {
                    sb.append("The qualifications are there, but they're not visible at a glance. ");
                } else if (signals.getTitleMatch() == TitleMatch.ADJACENT) {
                    sb.append("The title is close and experience is in range, but one or two signals are holding this back. ");
                } else {
                    sb.append("Some strong signals here, but not enough to be a clear yes. ");
                }
            }
            case WEAK_FIT -> {
                if (signals.isHasMissingMustHaves()) {
                    sb.append("The missing must-have skills are the decisive gap — this is a qualification mismatch, not a presentation issue. ");
                } else if (signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT) {
                    sb.append("The experience gap is the primary barrier for this role. ");
                } else {
                    sb.append("Multiple signals combined to a weak first impression. ");
                }
            }
        }

        // Add the pivoting insight — what's fixable vs structural
        if (verdict == Verdict.POSSIBLE_FIT && signals.isHasBuriedMustHaves()) {
            sb.append("This is a presentation problem, not a qualification problem — the skills exist but aren't visible where a recruiter looks. ");
        }

        // Highest-impact fix if any
        if (!fixes.isEmpty() && fixes.get(0).getImpact() == ImpactLevel.HIGH) {
            sb.append("The single highest-impact change: ").append(fixes.get(0).getAction().toLowerCase()).append(".");
        }

        return sb.toString().trim();
    }

    private int impactOrder(ImpactLevel level) {
        return switch (level) { case HIGH -> 0; case MEDIUM -> 1; case LOW -> 2; };
    }

    // ── Output record ─────────────────────────────────────────────────────────

    public record FeedbackOutput(List<Signal> signals, List<Fix> fixes, String summaryParagraph) {}
}
