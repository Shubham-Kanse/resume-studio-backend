package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(FeedbackGenerator.class);

    private final SentenceBank bank;

    public FeedbackGenerator(SentenceBank bank) {
        this.bank = bank;
    }

    public FeedbackOutput generate(ResumeSignals signals, Verdict verdict) {
        if (signals == null || verdict == null) {
            log.warn("Null signals or verdict provided to generate()");
            return new FeedbackOutput(List.of(), List.of(), "Unable to generate feedback due to missing data.");
        }
        
        List<Signal> signalList = buildSignals(signals);
        List<Fix> fixes = buildFixes(signals);
        String summary = buildSummary(signals, verdict, fixes);

        return new FeedbackOutput(signalList, fixes, summary);
    }

    // ── Signal list (max 6 for UI 2×3 grid) ──────────────────────────────────

    private List<Signal> buildSignals(ResumeSignals signals) {
        List<Signal> list = new ArrayList<>();

        // 1. Title match
        TitleMatch titleMatch = signals.getTitleMatch();
        SignalStatus titleStatus;
        if (titleMatch == null) {
            titleStatus = SignalStatus.WARN;
        } else {
            titleStatus = switch (titleMatch) {
                case EXACT, ADJACENT -> SignalStatus.PASS;
                case RELATED -> SignalStatus.WARN;
                case MISS -> SignalStatus.FAIL;
                default -> SignalStatus.WARN;
            };
        }
        list.add(signal("title_match", "Title match", titleStatus, SignalFriction.NONE, bank.titleObservation(signals), bank.titleInterpretation(signals), ImpactLevel.HIGH));

        // 2. YOE fit
        YoeFit yoeFit = signals.getYoeFit();
        SignalStatus yoeStatus;
        if (signals.isChronologyUnreliable()) {
            yoeStatus = SignalStatus.FAIL;
        } else if (yoeFit == null) {
            yoeStatus = SignalStatus.FAIL;
        } else {
            yoeStatus = switch (yoeFit) {
                case IN_RANGE -> SignalStatus.PASS;
                case UNDER_RANGE_MINOR, OVER_RANGE -> SignalStatus.WARN;
                case UNDER_RANGE_SIGNIFICANT, CANNOT_DETERMINE -> SignalStatus.FAIL;
            };
        }
        
        YoeState yoeState = signals.getYoeState();
        SignalFriction yoeFriction;
        if (signals.isChronologyUnreliable()) {
            yoeFriction = SignalFriction.HIGH;
        } else if (signals.isHasChronologyIssues()) {
            yoeFriction = SignalFriction.MEDIUM;
        } else if (yoeState == null) {
            yoeFriction = SignalFriction.HIGH;
        } else {
            yoeFriction = switch (yoeState) {
                case EXPLICIT -> SignalFriction.NONE;
                case CALCULABLE -> SignalFriction.LOW;
                case PARTIAL, INCONSISTENT_FORMAT -> SignalFriction.MEDIUM;
                default -> SignalFriction.HIGH;
            };
        }
        list.add(signal("yoe_fit", "Years of experience", yoeStatus, yoeFriction, bank.yoeObservation(signals), bank.yoeInterpretation(signals), ImpactLevel.HIGH));

        // 3. Must-have skills visible
        SignalStatus skillsStatus;
        SignalFriction skillsFriction;
        String skillsObservation;
        String skillsInterpretation;
        boolean hasJdSkills = signals.getMustHaveResults() != null && !signals.getMustHaveResults().isEmpty();
        if (!hasJdSkills) {
            // No JD skills were parsed — can't do skill matching
            skillsStatus = SignalStatus.WARN; skillsFriction = SignalFriction.NONE;
            skillsObservation = "No must-have skills could be extracted from the job description.";
            skillsInterpretation = "Paste a well-structured JD (with a Requirements section) to get targeted skill gap analysis.";
        } else if (signals.isHasMissingMustHaves()) {
            skillsStatus = SignalStatus.FAIL; skillsFriction = SignalFriction.HIGH;
            long missingCount = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MISSING).count();
            String first = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill).findFirst().orElse("required skill");
            skillsObservation = missingCount > 1
                ? first + " and " + (missingCount - 1) + " other must-have skills don't appear anywhere on your resume."
                : first + " doesn't appear anywhere on your resume.";
            skillsInterpretation = "Missing must-have skills are typically a decisive rejection signal. A recruiter scanning for these won't find them.";
        } else if (signals.isHasBuriedMustHaves()) {
            skillsStatus = SignalStatus.WARN; skillsFriction = SignalFriction.MEDIUM;
            skillsObservation = bank.skillsFormatObservation(signals);
            skillsInterpretation = bank.skillsFormatInterpretation(signals);
        } else {
            skillsStatus = SignalStatus.PASS; skillsFriction = SignalFriction.NONE;
            skillsObservation = bank.skillsFormatObservation(signals);
            skillsInterpretation = bank.skillsFormatInterpretation(signals);
        }
        list.add(signal("must_haves_visible", "Must-have skills visible", skillsStatus, skillsFriction, skillsObservation, skillsInterpretation, ImpactLevel.HIGH));

        // 4. Company context
        SignalStatus companyStatus = switch (signals.getCurrentCompanyTier()) {
            case FAANG, TIER_1 -> SignalStatus.PASS;
            case SCALE_UP, DESCRIBED -> SignalStatus.PASS;
            case STARTUP -> SignalStatus.WARN;
            case UNKNOWN -> SignalStatus.WARN;
        };
        list.add(signal("company_context", "Company context", companyStatus, SignalFriction.NONE, bank.companyObservation(signals), bank.companyInterpretation(signals), ImpactLevel.MEDIUM));

        // 5. Summary present and quality
        SignalStatus summaryStatus;
        if (!signals.isSummaryPresent()) summaryStatus = SignalStatus.WARN;
        else if (signals.isSummaryIsGeneric()) summaryStatus = SignalStatus.WARN;
        else if (signals.isSummaryMentionsYoe() && signals.isSummaryMentionsSkills()) summaryStatus = SignalStatus.PASS;
        else summaryStatus = SignalStatus.WARN;
        list.add(signal("summary_quality", "Summary section", summaryStatus, SignalFriction.NONE, bank.summaryObservation(signals), bank.summaryInterpretation(signals), ImpactLevel.MEDIUM));

        // 6. Title progression / format (pick most relevant)
        if (signals.isHasChronologyIssues()) {
            SignalStatus chronologyStatus = signals.isChronologyUnreliable() ? SignalStatus.FAIL : SignalStatus.WARN;
            SignalFriction chronologyFriction = signals.isChronologyUnreliable() ? SignalFriction.HIGH : SignalFriction.MEDIUM;
            list.add(signal("chronology", "Career chronology", chronologyStatus, chronologyFriction, bank.chronologyObservation(signals), bank.chronologyInterpretation(signals), ImpactLevel.MEDIUM));
        } else if (signals.isFormatWallOfText() || signals.isFormatHasPhoto() || signals.isFormatTooManyPages()) {
            SignalStatus formatStatus = SignalStatus.WARN;
            list.add(signal("format_quality", "Layout & formatting", formatStatus, SignalFriction.MEDIUM, "Formatting issues detected that increase scan friction.", "Layout problems make the document harder to read at speed.", ImpactLevel.MEDIUM));
        } else {
            TitleProgression prog = signals.getTitleProgression();
            SignalStatus progressionStatus;
            String progObs;
            String progInterp;
            switch (prog == null ? TitleProgression.UNKNOWN : prog) {
                case GROWING -> {
                    progressionStatus = SignalStatus.PASS;
                    progObs = "Your career shows an upward title trajectory.";
                    progInterp = "Title growth is a positive trust signal — it shows a recruiter you've earned more responsibility over time.";
                }
                case FLAT -> {
                    progressionStatus = SignalStatus.WARN;
                    progObs = "Your title has remained at the same level across roles.";
                    progInterp = "Flat progression isn't disqualifying, but a recruiter will expect to see growing scope or impact in the bullet points instead.";
                }
                case REGRESSION -> {
                    progressionStatus = SignalStatus.WARN;
                    progObs = "Your most recent title is lower than a previous one.";
                    progInterp = "A step down in title raises questions without context. If it was intentional (pivot, move abroad, company stage), address it briefly in your summary.";
                }
                default -> {
                    // UNKNOWN: not enough roles with readable seniority markers
                    progressionStatus = SignalStatus.WARN;
                    progObs = "Only one substantive role on record — trajectory can't be established from a single position.";
                    progInterp = "This isn't a negative signal at your career stage. As you add more roles, showing upward progression will matter increasingly.";
                }
            }
            list.add(signal("title_progression", "Career trajectory", progressionStatus, SignalFriction.NONE, progObs, progInterp, ImpactLevel.LOW));
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
                fixes.add(fix(rank++, "must_haves_visible", "Add these missing must-have skills: " + skillList, "These are the primary technical requirements for this role. Their absence is typically a decisive rejection signal.", "Only add skills you genuinely have. If these are gaps, consider targeting roles that match your current stack.", ImpactLevel.HIGH));
            }
        }

        // Buried must-haves
        if (signals.isHasBuriedMustHaves()) {
            List<SkillMatchResult> buried = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.BURIED || r.getVisibility() == SkillVisibility.MID).toList();
            if (!buried.isEmpty()) {
                String skillList = buried.stream().map(SkillMatchResult::getJdSkill)
                    .reduce((a, b) -> a + ", " + b).orElse("key skills");
                fixes.add(fix(rank++, "must_haves_visible", "Move these skills to your skills section: " + skillList, bank.skillVisibilityInterpretation(buried.get(0)), "Add them to your skills section. Example: 'Programming: " + buried.get(0).getJdSkill() + ", ...'", ImpactLevel.HIGH));
            }
        }

        // Summary missing or weak
        String summaryAction = bank.summaryAction(signals);
        if (summaryAction != null) {
            fixes.add(fix(rank++, "summary_quality", !signals.isSummaryPresent() ? "Add a professional summary at the top of your resume" : "Rewrite your summary with technical specifics", bank.summaryInterpretation(signals), summaryAction, ImpactLevel.HIGH));
        }

        // Skills format
        String formatAction = bank.skillsFormatAction(signals);
        if (formatAction != null) {
            fixes.add(fix(rank++, "must_haves_visible", "Restructure your skills section", bank.skillsFormatInterpretation(signals), formatAction, ImpactLevel.MEDIUM));
        }

        // Company no context
        String companyAction = bank.companyAction(signals);
        if (companyAction != null) {
            fixes.add(fix(rank++, "company_context", "Add a descriptor to your current company", bank.companyInterpretation(signals), companyAction, ImpactLevel.MEDIUM));
        }

        // Title mismatch
        String titleAction = bank.titleAction(signals);
        if (titleAction != null) {
            fixes.add(fix(rank++, "title_match", "Bridge the title gap in your summary", bank.titleInterpretation(signals), titleAction, ImpactLevel.MEDIUM));
        }

        // YOE issues
        String yoeAction = bank.yoeAction(signals);
        if (yoeAction != null) {
            fixes.add(fix(rank++, "yoe_fit", signals.isChronologyUnreliable() ? "Fix the chronology of your resume" : "Clarify your years of experience", signals.isChronologyUnreliable() ? bank.chronologyInterpretation(signals) : bank.yoeInterpretation(signals), signals.isChronologyUnreliable() ? bank.chronologyAction() : yoeAction, signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT ? ImpactLevel.HIGH : ImpactLevel.MEDIUM));
        }

        if (signals.isHasChronologyIssues() && !signals.isChronologyUnreliable() && yoeAction == null) {
            fixes.add(fix(rank++, "yoe_fit", "Tighten the chronology of your resume", bank.chronologyInterpretation(signals), bank.chronologyAction(), ImpactLevel.MEDIUM));
        }

        // Unexplained gap
        if (signals.isHasUnexplainedGap() && !signals.isChronologyUnreliable()) {
            fixes.add(fix(rank++, "yoe_fit", "Label your career gap", bank.gapObservation(signals), bank.gapAction(), ImpactLevel.MEDIUM));
        }

        // Filename
        if (!signals.isFilenameProfessional()) {
            fixes.add(fix(rank++, "filename", "Rename your resume file", bank.filenameInterpretation(signals), bank.filenameAction(), ImpactLevel.LOW));
        }

        // Formatting issues
        if (signals.isFormatHasPhoto()) {
            fixes.add(fix(rank++, "format_quality", "Remove the photo from your resume", "Photos are non-standard for tech roles in most markets and introduce unconscious bias risk. Most ATS systems strip them anyway.", "Remove the photo. Your work speaks for itself.", ImpactLevel.LOW));
        }

        // Skill age mismatch
        if (signals.isHasSkillAgeMismatch()) {
            fixes.add(fix(rank++, "skill_age_mismatch", "Correct your stated years of experience for a skill", "An impossible years-of-experience claim undermines credibility immediately.", bank.skillAgeMismatchAction(signals), ImpactLevel.HIGH));
        }

        // Bullet quality - weak verbs
        if (signals.getImpactVerbRatio() < 0.5) {
            fixes.add(fix(rank++, "bullet_quality", String.format("Only %.0f%% of your bullets start with impact verbs", signals.getImpactVerbRatio() * 100), "Weak verbs like 'responsible for' or 'worked on' don't convey ownership or results. Recruiters scan for action and impact.", "Rewrite bullets to start with strong verbs: Built, Designed, Led, Reduced, Increased, Automated, Migrated, Scaled.", ImpactLevel.MEDIUM));
        }

        // Bullet quality - no metrics
        if (signals.getMetricDensity() < 0.3) {
            fixes.add(fix(rank++, "bullet_quality", String.format("Only %.0f%% of your bullets include quantified results", signals.getMetricDensity() * 100), "Unquantified claims are vague. Numbers make impact concrete and memorable.", "Add metrics: '...reduced latency by 75%', '...serving 5M daily transactions', '...adopted by 40% more clients'.", ImpactLevel.MEDIUM));
        }

        // Sort by impact
        fixes.sort(Comparator.comparingInt(f -> impactOrder(f.getImpact())));
        for (int i = 0; i < fixes.size(); i++) fixes.get(i).setRank(i + 1);

        return fixes;
    }

    // ── Summary paragraph ─────────────────────────────────────────────────────

    private String buildSummary(ResumeSignals signals, Verdict verdict, List<Fix> fixes) {
        String yoe = signals.getCalculatedYoe() != null && signals.getCalculatedYoe() > 0
            ? String.format("%.1f", signals.getCalculatedYoe()).replaceAll("\\.0$", "") : null;
        String jdTitle = signals.getJdTitle() != null ? signals.getJdTitle() : "this role";
        String candidateTitle = signals.getCandidateTitle();
        String jdRange = signals.getJdYoeMin() != null
            ? (signals.getJdYoeMax() == null ? signals.getJdYoeMin().intValue() + "+" : signals.getJdYoeMin().intValue() + "–" + signals.getJdYoeMax().intValue())
            : null;

        String topMissingSkill = signals.getMustHaveResults() != null
            ? signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill).findFirst().orElse(null)
            : null;
        String topBuriedSkill = signals.getMustHaveResults() != null
            ? signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.BURIED || r.getVisibility() == SkillVisibility.MID)
                .map(SkillMatchResult::getJdSkill).findFirst().orElse(null)
            : null;

        boolean titleOk = signals.getTitleMatch() != null && 
            (signals.getTitleMatch() == TitleMatch.EXACT || signals.getTitleMatch() == TitleMatch.ADJACENT);
        boolean yoeOk = signals.getYoeFit() != null && 
            (signals.getYoeFit() == YoeFit.IN_RANGE || signals.getYoeFit() == YoeFit.OVER_RANGE);
        boolean yoeClose = signals.getYoeFit() != null && signals.getYoeFit() == YoeFit.UNDER_RANGE_MINOR;

        StringBuilder sb = new StringBuilder();

        switch (verdict) {
            case STRONG_FIT -> {
                sb.append("The foundation is solid");
                if (candidateTitle != null) sb.append(" — \"").append(candidateTitle).append("\" aligns directly with the role");
                if (yoe != null && jdRange != null) sb.append(", ").append(yoe).append(" years of experience is in range");
                sb.append(", and the must-have skills are visible at a glance.");
                CompanyTier tier = signals.getCurrentCompanyTier();
                if (tier != null && (tier == CompanyTier.FAANG || tier == CompanyTier.TIER_1)) {
                    sb.append(" The company background adds further credibility.");
                }
            }
            case POSSIBLE_FIT -> {
                if (topBuriedSkill != null && !signals.isHasMissingMustHaves()) {
                    sb.append("The foundation is there");
                    if (titleOk) sb.append(" — your title aligns");
                    if (yoeOk && yoe != null) sb.append(" and ").append(yoe).append(" years of experience is in range");
                    else if (yoeClose && yoe != null && jdRange != null) sb.append(" and ").append(yoe).append(" years is close to the ").append(jdRange).append(" requirement");
                    sb.append(". But ").append(topBuriedSkill).append(", a core requirement for ").append(jdTitle);
                    sb.append(", isn't visible at a glance. A recruiter scanning your skills section right now would not see it and would likely move on. ");
                    sb.append("This is a presentation problem, not a qualification problem.");
                } else if (yoeClose && titleOk) {
                    sb.append("Strong title match");
                    if (signals.isAllMustHavesVisible()) sb.append(" and skills are well-presented");
                    sb.append(", but ");
                    if (yoe != null && jdRange != null) sb.append(yoe).append(" years falls just short of the ").append(jdRange).append(" requirement. ");
                    sb.append("Close enough that a strong skills section can compensate — recruiters treat YOE as a guideline, not a hard rule.");
                } else if (!titleOk && signals.isAllMustHavesVisible()) {
                    sb.append("The skills are there and well-presented, but the title gap is creating hesitation. ");
                    sb.append("A recruiter can't immediately tell from the header that this is the right profile for ").append(jdTitle).append(".");
                } else {
                    sb.append("Some strong signals here");
                    if (titleOk) sb.append(" — title aligns");
                    if (yoeOk) sb.append(yoeOk && titleOk ? ", experience is in range" : " — experience is in range");
                    sb.append(", but not enough to be a clear yes without further review.");
                }
            }
            case WEAK_FIT -> {
                if (signals.isChronologyUnreliable()) {
                    sb.append("The chronology is the primary barrier. The work and education dates do not form a timeline a recruiter can trust.");
                } else if (topMissingSkill != null) {
                    List<String> missingSkills = signals.getMustHaveResults().stream()
                        .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
                        .map(SkillMatchResult::getJdSkill)
                        .toList();
                    int missingCount = missingSkills.size();
                    int totalCount = signals.getMustHaveResults().size();

                    if (missingCount == 1) {
                        sb.append(topMissingSkill).append(", a core requirement for ").append(jdTitle);
                        sb.append(", doesn't appear on this resume. This is a qualification gap, not a presentation issue.");
                    } else {
                        int limit = missingCount <= 5 ? missingCount : 4;
                        List<String> named = missingSkills.stream().limit(limit).toList();
                        String skillList = named.stream().reduce((a, b) -> a + ", " + b).orElse(topMissingSkill);
                        if (missingCount > limit) skillList += ", and " + (missingCount - limit) + " more";
                        sb.append("This resume is missing the required stack for ").append(jdTitle).append(": ")
                          .append(skillList).append(".");
                        if (totalCount > 0) {
                            sb.append(" ").append(missingCount).append(" of ").append(totalCount)
                              .append(" required skills are absent.");
                        }
                        sb.append(" This is a qualification gap, not a presentation issue.");
                    }
                } else if (signals.getYoeFit() != null && signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT) {
                    sb.append("The experience gap is the primary barrier");
                    if (yoe != null && jdRange != null) sb.append(" — ").append(yoe).append(" years against a ").append(jdRange).append(" requirement");
                    sb.append(". Skills alone can't bridge a gap this size in a 10-second pass.");
                } else {
                    sb.append("Multiple signals combined to a weak first impression.");
                    if (!titleOk) sb.append(" The title mismatch is the biggest barrier.");
                }
            }
        }

        return sb.toString().trim();
    }

    private int impactOrder(ImpactLevel level) {
        return switch (level) { case HIGH -> 0; case MEDIUM -> 1; case LOW -> 2; };
    }

    /** Convenience factory — mirrors the old positional constructor signature. */
    private static Fix fix(int rank, String signalId, String action, String reason, String example, ImpactLevel impact) {
        Fix f = new Fix();
        f.setRank(rank);
        f.setSignalId(signalId);
        f.setAction(action);
        f.setReason(reason);
        f.setBeforeAfter(new Fix.BeforeAfter(example, null));
        f.setImpact(impact);
        return f;
    }

    /** Convenience factory — mirrors the old positional Signal constructor signature. */
    private static Signal signal(String id, String label, SignalStatus status, SignalFriction friction,
                                  String observation, String interpretation, ImpactLevel impact) {
        Signal s = new Signal();
        s.setId(id);
        s.setLabel(label);
        s.setStatus(status);
        s.setFriction(friction);
        s.setObservation(observation);
        s.setInterpretation(interpretation);
        s.setImpact(impact);
        // Confidence: PASS signals are HIGH, WARN are MEDIUM, FAIL are HIGH (we're confident it's a problem)
        s.setConfidence(status == SignalStatus.WARN ? Confidence.MEDIUM : Confidence.HIGH);
        return s;
    }

    // ── Output record ─────────────────────────────────────────────────────────

    public record FeedbackOutput(List<Signal> signals, List<Fix> fixes, String summaryLine) {}
}
