package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
    private final SentenceBankOntologyService ontologyBank;

    public FeedbackGenerator(SentenceBank bank, SentenceBankOntologyService ontologyBank) {
        this.bank = bank;
        this.ontologyBank = ontologyBank;
    }

    /**
     * Get observation from ontology with token interpolation, falling back to SentenceBank.
     * seed = hash of signalId+tier for determinism.
     */
    private String obs(String signalId, String tier, Map<String, String> tokens) {
        int seed = (signalId + tier).hashCode();
        String result = ontologyBank.observation(signalId, tier, tokens, seed);
        return result != null ? result : null; // caller handles null fallback
    }

    private String interp(String signalId, String tier, Map<String, String> tokens) {
        int seed = (signalId + tier + "i").hashCode();
        String result = ontologyBank.interpretation(signalId, tier, tokens, seed);
        return result != null ? result : null;
    }

    private String act(String signalId, String tier, Map<String, String> tokens) {
        int seed = (signalId + tier + "a").hashCode();
        return ontologyBank.action(signalId, tier, tokens, seed);
    }

    public FeedbackOutput generate(ResumeSignals signals, Verdict verdict) {
        return generate(signals, verdict, null);
    }

    public FeedbackOutput generate(ResumeSignals signals, Verdict verdict, com.resumestudio.reviewer.model.Resume resume) {
        if (signals == null || verdict == null) {
            log.warn("Null signals or verdict provided to generate()");
            return new FeedbackOutput(List.of(), List.of(), "Unable to generate feedback due to missing data.");
        }
        
        List<Signal> signalList = buildSignals(signals, resume);
        List<Fix> fixes = buildFixes(signals);
        String summary = buildSummary(signals, verdict, fixes);

        return new FeedbackOutput(signalList, fixes, summary);
    }

    // ── Signal list — synthesised cross-signal insights ───────────────────────
    //
    // A professional reviewer doesn't list signals in isolation.
    // They synthesise: "Your background is credible but the stack gap is the blocker."
    // We produce 3-4 insights that combine related signals into a coherent story.

    private List<Signal> buildSignals(ResumeSignals signals) {
        return buildSignals(signals, null);
    }

    private List<Signal> buildSignals(ResumeSignals signals, com.resumestudio.reviewer.model.Resume resume) {
        List<Signal> list = new ArrayList<>();

        list.add(buildFitInsight(signals));
        list.add(buildSkillInsight(signals, resume));
        list.add(buildPresentationInsight(signals, resume));

        Signal trustInsight = buildTrustInsight(signals);
        if (trustInsight != null) list.add(trustInsight);

        return list;
    }

    /**
     * Insight 1 — Candidate fit.
     * Combines title match + YOE fit + company tier into one verdict.
     * Only surfaces what's actually interesting — not "title matches" when it obviously does.
     */
    private Signal buildFitInsight(ResumeSignals signals) {
        TitleMatch titleMatch = signals.getTitleMatch() != null ? signals.getTitleMatch() : TitleMatch.MISS;
        YoeFit yoeFit = signals.getYoeFit() != null ? signals.getYoeFit() : YoeFit.CANNOT_DETERMINE;
        CompanyTier tier = signals.getCurrentCompanyTier() != null ? signals.getCurrentCompanyTier() : CompanyTier.UNKNOWN;

        boolean titleOk = titleMatch == TitleMatch.EXACT || titleMatch == TitleMatch.ADJACENT;
        boolean yoeOk = yoeFit == YoeFit.IN_RANGE;
        boolean tierStrong = tier == CompanyTier.FAANG || tier == CompanyTier.TIER_1;
        boolean yoeShort = yoeFit == YoeFit.UNDER_RANGE_SIGNIFICANT;
        boolean titleMiss = titleMatch == TitleMatch.MISS;

        String candidateTitle = signals.getCandidateTitle() != null ? signals.getCandidateTitle() : "your title";
        String jdTitle = signals.getJdTitle() != null ? signals.getJdTitle() : "this role";
        String yoeStr = signals.getCalculatedYoe() != null ? String.format("%.1f", signals.getCalculatedYoe()).replaceAll("\\.0$", "") : "unknown";
        String yoeRange = signals.getJdYoeMin() != null
            ? signals.getJdYoeMin().intValue() + (signals.getJdYoeMax() != null ? "–" + signals.getJdYoeMax().intValue() : "+")
            : "required";
        String company = signals.getCurrentCompanyName() != null ? signals.getCurrentCompanyName() : "your company";

        Map<String, String> tokens = Map.of(
            "candidate_title", candidateTitle,
            "jd_title", jdTitle,
            "calculated_yoe", yoeStr,
            "jd_yoe_min", signals.getJdYoeMin() != null ? String.valueOf(signals.getJdYoeMin().intValue()) : "",
            "jd_yoe_max", signals.getJdYoeMax() != null ? String.valueOf(signals.getJdYoeMax().intValue()) : "",
            "company_name", company
        );

        SignalStatus status;
        String titleOntologyTier;
        String obsOverride = null, interpOverride = null;

        if (titleMiss && yoeShort) {
            status = SignalStatus.FAIL;
            titleOntologyTier = "CRITICAL";
            obsOverride = candidateTitle + " and " + yoeStr + " years of experience both fall short of what " + jdTitle + " expects.";
            interpOverride = "Two hard filters failing simultaneously makes this a difficult application to advance without significant tailoring.";
        } else if (titleMiss) {
            status = SignalStatus.WARN;
            titleOntologyTier = "POOR";
        } else if (yoeShort) {
            status = SignalStatus.WARN;
            titleOntologyTier = "FAIR";
            obsOverride = yoeStr + " years of experience against a " + yoeRange + " year requirement.";
            interpOverride = tierStrong
                ? "The experience gap is real, but " + company + " pedigree adds credibility that partially compensates."
                : "This is a meaningful gap. Strong skill coverage and a targeted summary are essential to stay in contention.";
        } else if (titleOk && yoeOk && tierStrong) {
            status = SignalStatus.PASS;
            titleOntologyTier = "EXCELLENT";
            obsOverride = "Title, experience level, and " + company + " background all align with what this role expects.";
            interpOverride = "The profile clears the credibility bar. Whether it advances depends entirely on skill coverage.";
        } else if (titleOk && yoeOk) {
            status = SignalStatus.PASS;
            titleOntologyTier = "GOOD";
        } else {
            status = SignalStatus.WARN;
            titleOntologyTier = "FAIR";
        }

        // Use ontology sentences, fall back to overrides
        String observation = obsOverride != null ? obsOverride : obs("title_match", titleOntologyTier, tokens);
        String interpretation = interpOverride != null ? interpOverride : interp("title_match", titleOntologyTier, tokens);
        if (observation == null) observation = bank.titleObservation(signals);
        if (interpretation == null) interpretation = bank.titleInterpretation(signals);

        return signal("candidate_fit", "Candidate fit", status, SignalFriction.NONE, observation, interpretation, ImpactLevel.HIGH);
    }

    /**
     * Insight 2 — Skill coverage.
     * The single most important signal. Specific about what's missing and what's buried.
     */
    private Signal buildSkillInsight(ResumeSignals signals, com.resumestudio.reviewer.model.Resume resume) {
        boolean hasJdSkills = signals.getMustHaveResults() != null && !signals.getMustHaveResults().isEmpty();

        if (!hasJdSkills) {
            return signal("skill_coverage", "Skill coverage", SignalStatus.WARN, SignalFriction.NONE,
                "No required skills could be extracted from the job description.",
                "Paste a structured JD with a Requirements section to get targeted skill gap analysis.",
                ImpactLevel.HIGH);
        }

        long total = signals.getMustHaveResults().size();
        long missing = signals.getMustHaveResults().stream()
            .filter(r -> r.getVisibility() == SkillVisibility.MISSING).count();
        long buried = signals.getMustHaveResults().stream()
            .filter(r -> r.getVisibility() == SkillVisibility.BURIED).count();
        long found = total - missing;

        String firstMissing = signals.getMustHaveResults().stream()
            .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
            .map(SkillMatchResult::getJdSkill).findFirst().orElse("a required skill");
        String firstBuried = signals.getMustHaveResults().stream()
            .filter(r -> r.getVisibility() == SkillVisibility.BURIED)
            .map(SkillMatchResult::getJdSkill).findFirst().orElse("a required skill");

        // Evidence: first matched skill location or first missing skill name
        String evidence = buildSkillEvidence(signals, resume);

        Map<String, String> tokens = Map.of("skill_name", firstMissing);

        if (missing == 0 && buried == 0) {
            String o = obs("skills_visibility", "EXCELLENT", tokens);
            String i = interp("skills_visibility", "EXCELLENT", tokens);
            // NLU: check if skills are evidenced deeply or just listed
            String nluNote = "";
            if (!signals.getShallowSkills().isEmpty()) {
                nluNote = " However, " + signals.getShallowSkills().get(0) + " appears only once — depth of expertise is unclear.";
            }
            return signal("skill_coverage", "Skill coverage", SignalStatus.PASS, SignalFriction.NONE,
                (o != null ? o : "All " + total + " required skills are present and visible.") + nluNote,
                i != null ? i : "A recruiter scanning for the core stack will find everything they need.",
                ImpactLevel.HIGH, evidence);
        }

        if (missing == 0) {
            Map<String, String> buriedTokens = Map.of("skill_name", firstBuried);
            String o = obs("skills_visibility", "FAIR", buriedTokens);
            String i = interp("skills_visibility", "FAIR", buriedTokens);
            String obsText = o != null ? o : (buried == 1
                ? firstBuried + " is only visible in an older role — not in your skills section."
                : buried + " required skills (including " + firstBuried + ") are buried in older roles.");
            String interpText = i != null ? i : "You have the skills — the problem is visibility. A recruiter scanning in 10 seconds won't reach old bullet points.";
            return signal("skill_coverage", "Skill coverage", SignalStatus.WARN, SignalFriction.MEDIUM, obsText, interpText, ImpactLevel.HIGH, evidence);
        }

        SignalStatus status = missing > total / 2 ? SignalStatus.FAIL : SignalStatus.WARN;
        String ontologyTier = status == SignalStatus.FAIL ? "CRITICAL" : "POOR";

        String o = obs("skills_visibility", ontologyTier, tokens);
        String i = interp("skills_visibility", ontologyTier, tokens);

        String obsText = o != null ? o : (missing == total
            ? "None of the " + total + " required skills appear on your resume."
            : found + " of " + total + " required skills found. " + firstMissing + (missing > 1 ? " and " + (missing - 1) + " others are missing." : " is missing."));

        String interpText = i != null ? i : (missing >= total * 0.7
            ? "The stack gap is too wide for a recruiter to bridge mentally."
            : missing >= total * 0.4
                ? "Significant gaps. A recruiter will note the missing skills and likely move on."
                : "A few gaps. Addressable with targeted additions to your skills section.");

        return signal("skill_coverage", "Skill coverage", status,
            status == SignalStatus.FAIL ? SignalFriction.HIGH : SignalFriction.MEDIUM,
            obsText, interpText, ImpactLevel.HIGH, evidence);
    }

    /** Extracts a short verbatim evidence snippet for the skill signal. */
    private String buildSkillEvidence(ResumeSignals signals, com.resumestudio.reviewer.model.Resume resume) {
        if (resume == null || signals.getMustHaveResults() == null) return null;
        // Show where the first found skill appears
        return signals.getMustHaveResults().stream()
            .filter(r -> r.getVisibility() != SkillVisibility.MISSING && r.getSourceText() != null && !r.getSourceText().isBlank())
            .map(r -> r.getSourceText().length() > 80 ? r.getSourceText().substring(0, 80) + "…" : r.getSourceText())
            .findFirst().orElse(null);
    }

    /**
     * Insight 3 — Resume presentation.
     * Combines summary quality + skills format + bullet quality into one verdict.
     * Only surfaces what's actually wrong — doesn't praise the obvious.
     */
    private Signal buildPresentationInsight(ResumeSignals signals, com.resumestudio.reviewer.model.Resume resume) {
        boolean summaryMissing = !signals.isSummaryPresent();
        boolean summaryGeneric = signals.isSummaryIsGeneric();
        boolean summaryWeak = summaryMissing || summaryGeneric
            || (!signals.isSummaryMentionsSkills() && !signals.isSummaryMentionsYoe());
        boolean skillsFormatPoor = signals.getSkillsFormat() == SkillsFormat.NO_SECTION
            || signals.getSkillsFormat() == SkillsFormat.GENERIC_ONLY
            || signals.getSkillsFormat() == SkillsFormat.PROSE;
        boolean lowImpactBullets = signals.getImpactVerbRatio() < 0.4;
        boolean lowMetrics = signals.getMetricDensity() < 0.3;

        int issues = (summaryWeak ? 1 : 0) + (skillsFormatPoor ? 1 : 0) + (lowImpactBullets || lowMetrics ? 1 : 0);

        if (issues == 0) {
            String o = obs("summary", "EXCELLENT", Map.of());
            String i = interp("summary", "EXCELLENT", Map.of());
            return signal("presentation", "Resume presentation", SignalStatus.PASS, SignalFriction.NONE,
                o != null ? o : "Summary, skills section, and bullet quality are all working.",
                i != null ? i : "The document is doing its job — no presentation friction to fix.",
                ImpactLevel.MEDIUM);
        }

        SignalStatus status = issues >= 2 ? SignalStatus.WARN : SignalStatus.WARN;
        String obs;
        String interp;

        if (summaryMissing && skillsFormatPoor) {
            obs = "No summary and no structured skills section — a recruiter has to hunt for context and stack.";
            interp = "These two sections are the first things a recruiter reads. Without them, the document forces extra work before any value is delivered.";
        } else if (summaryMissing) {
            obs = "No summary. The recruiter goes straight to experience with no framing of who you are or why you fit.";
            interp = lowImpactBullets
                ? "Combined with weak bullet language, there's no strong hook anywhere in the top half of the document."
                : "Your experience bullets are solid, but a 2-line summary would orient the recruiter before they get there.";
        } else if (summaryGeneric) {
            obs = "Your summary uses generic language that doesn't signal fit for this specific role.";
            interp = "A recruiter reading 'passionate team player' learns nothing about your technical profile. The summary slot is wasted.";
        } else if (!signals.isSummaryMentionsSkills()) {
            obs = "Your summary doesn't mention the core skills this role requires.";
            interp = "The summary is your one chance to immediately signal fit. Not mentioning the required stack is a missed opportunity.";
        } else if (skillsFormatPoor) {
            obs = bank.skillsFormatObservation(signals);
            interp = bank.skillsFormatInterpretation(signals);
        } else if (lowImpactBullets && lowMetrics) {
            obs = "Most bullets describe responsibilities rather than outcomes. No quantified results visible.";
            interp = "Recruiters scan for numbers and impact verbs. 'Responsible for X' tells them nothing about what you actually achieved.";
        } else if (lowMetrics) {
            obs = "Fewer than 30% of your bullets include quantified results.";
            interp = "Numbers are the fastest way to signal impact. 'Reduced latency by 40%' is read in 1 second. 'Improved performance' is skipped.";
        } else {
            obs = "Bullet language leans on weak verbs — 'assisted', 'supported', 'helped'.";
            interp = "Weak verbs signal a supporting role, not ownership. Replace with action verbs that show you drove the outcome.";
        }

        // Evidence: first weak bullet as a concrete example
        String presentationEvidence = null;
        if (resume != null && resume.getEnrichedBullets() != null) {
            presentationEvidence = resume.getEnrichedBullets().stream()
                .filter(b -> !b.metricDetected() && b.specificityScore() < 4.0)
                .map(com.resumestudio.reviewer.nlp.BulletEnricher.EnrichedBullet::text)
                .filter(t -> t != null && !t.isBlank())
                .map(t -> t.length() > 100 ? t.substring(0, 100) + "…" : t)
                .findFirst().orElse(null);
        }
        if (presentationEvidence == null && resume != null && resume.getSummaryText() != null
                && !resume.getSummaryText().isBlank() && summaryGeneric) {
            String s = resume.getSummaryText().trim();
            presentationEvidence = s.length() > 100 ? s.substring(0, 100) + "…" : s;
        }

        return signal("presentation", "Resume presentation", status, SignalFriction.MEDIUM, obs, interp, ImpactLevel.MEDIUM, presentationEvidence);
    }

    /**
     * Insight 4 — Trust signals.
     * Only shown when there's an actual issue: chronology problems, anomalies, title inflation.
     * Returns null if everything is clean — no need to say "no trust issues found".
     */
    private Signal buildTrustInsight(ResumeSignals signals) {
        if (signals.isChronologyUnreliable()) {
            return signal("trust", "Credibility", SignalStatus.FAIL, SignalFriction.HIGH,
                bank.chronologyObservation(signals),
                "An unreliable chronology undermines every other claim on the resume. Recruiters can't verify experience level.",
                ImpactLevel.HIGH);
        }
        if (signals.isHasUnexplainedGap()) {
            String gapDesc = !signals.getGapDescriptions().isEmpty() ? signals.getGapDescriptions().get(0) : "An unexplained gap";
            return signal("trust", "Credibility", SignalStatus.WARN, SignalFriction.MEDIUM,
                gapDesc + " with no label.",
                "Unlabelled gaps raise questions. A one-word label — 'Career break', 'MSc', 'Freelance' — removes the ambiguity entirely.",
                ImpactLevel.MEDIUM);
        }
        if (signals.isHasSkillAgeMismatch()) {
            return signal("trust", "Credibility", SignalStatus.WARN, SignalFriction.MEDIUM,
                signals.getSkillAgeMismatchDetail() != null ? signals.getSkillAgeMismatchDetail() : "A skill YOE claim exceeds the technology's age.",
                "This type of error is noticed by technical reviewers and damages credibility across the whole document.",
                ImpactLevel.MEDIUM);
        }
        if (signals.isHasTitleInflation()) {
            return signal("trust", "Credibility", SignalStatus.WARN, SignalFriction.MEDIUM,
                "Your most recent title suggests senior scope, but the bullet language reads as a supporting role.",
                "A technical recruiter will notice the mismatch between title and bullet evidence. Rewrite bullets to reflect ownership, not assistance.",
                ImpactLevel.MEDIUM);
        }
        return null; // clean — don't show this signal at all
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
                fixes.add(fix(rank++, "skill_match", "Add these missing must-have skills: " + skillList, "These are the primary technical requirements for this role. Their absence is typically a decisive rejection signal.", "Only add skills you genuinely have. If these are gaps, consider targeting roles that match your current stack.", ImpactLevel.HIGH));
            }
        }

        // Buried must-haves
        if (signals.isHasBuriedMustHaves()) {
            List<SkillMatchResult> buried = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.BURIED || r.getVisibility() == SkillVisibility.MID).toList();
            if (!buried.isEmpty()) {
                String skillList = buried.stream().map(SkillMatchResult::getJdSkill)
                    .reduce((a, b) -> a + ", " + b).orElse("key skills");
                fixes.add(fix(rank++, "skill_match", "Move these skills to your skills section: " + skillList, bank.skillVisibilityInterpretation(buried.get(0)), "Add them to your skills section. Example: 'Programming: " + buried.get(0).getJdSkill() + ", ...'", ImpactLevel.HIGH));
            }
        }

        // Summary missing or weak
        String summaryTier = !signals.isSummaryPresent() ? "CRITICAL" : signals.isSummaryIsGeneric() ? "POOR" : "FAIR";
        String summaryActionText = act("summary", summaryTier, Map.of());
        if (summaryActionText == null) summaryActionText = bank.summaryAction(signals);
        if (summaryActionText != null) {
            String summaryInterpText = interp("summary", summaryTier, Map.of());
            if (summaryInterpText == null) summaryInterpText = bank.summaryInterpretation(signals);
            fixes.add(fix(rank++, "summary",  // Issue 3: was "summary_quality"
                !signals.isSummaryPresent() ? "Add a professional summary at the top of your resume" : "Rewrite your summary with technical specifics",
                summaryInterpText, summaryActionText, ImpactLevel.HIGH));
        }

        // Skills format
        String formatAction = bank.skillsFormatAction(signals);
        if (formatAction != null) {
            fixes.add(fix(rank++, "skill_match", "Restructure your skills section", bank.skillsFormatInterpretation(signals), formatAction, ImpactLevel.MEDIUM));
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
            String bulletTier = signals.getImpactVerbRatio() < 0.2 ? "CRITICAL" : "POOR";
            String bulletObs = obs("bullet_quality", bulletTier, Map.of("bullet_count", String.valueOf((int)(signals.getImpactVerbRatio() * 100))));
            String bulletAct = act("bullet_quality", bulletTier, Map.of());
            fixes.add(fix(rank++, "bullets",  // Issue 3: was "bullet_quality"
                bulletObs != null ? bulletObs : String.format("Only %.0f%% of your bullets start with impact verbs", signals.getImpactVerbRatio() * 100),
                "Weak verbs like 'responsible for' or 'worked on' don't convey ownership or results.",
                bulletAct != null ? bulletAct : "Rewrite bullets to start with strong verbs: Built, Designed, Led, Reduced, Increased, Automated.",
                ImpactLevel.MEDIUM));
        }

        // Bullet quality - no metrics
        if (signals.getMetricDensity() < 0.3) {
            String metricAct = act("bullet_quality", "POOR", Map.of());
            fixes.add(fix(rank++, "bullets",  // Issue 3: was "bullet_quality"
                String.format("Only %.0f%% of your bullets include quantified results", signals.getMetricDensity() * 100),
                "Unquantified claims are vague. Numbers make impact concrete and memorable.",
                metricAct != null ? metricAct : "Add metrics: '...reduced latency by 75%', '...serving 5M daily transactions'.",
                ImpactLevel.MEDIUM));
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
            case NO_FIT -> {
                // Honest, non-discouraging summary for resumes that don't match the role's
                // core requirements. We name the dominant barrier(s) so the candidate knows
                // exactly why and can either reposition or target a different role.
                List<String> missingSkills = signals.getMustHaveResults() != null
                    ? signals.getMustHaveResults().stream()
                        .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
                        .map(SkillMatchResult::getJdSkill)
                        .toList()
                    : List.of();
                int missingCount = missingSkills.size();
                int totalCount = signals.getMustHaveResults() != null ? signals.getMustHaveResults().size() : 0;

                sb.append("This resume does not match the core requirements for ").append(jdTitle).append(".");
                if (missingCount > 0 && totalCount > 0) {
                    int limit = Math.min(missingCount, 4);
                    String skillList = missingSkills.stream().limit(limit)
                        .reduce((a, b) -> a + ", " + b).orElse("");
                    if (missingCount > limit) skillList += ", and " + (missingCount - limit) + " more";
                    sb.append(" ").append(missingCount).append(" of ").append(totalCount)
                      .append(" must-have skills are absent (").append(skillList).append(").");
                }
                if (signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT && yoe != null && jdRange != null) {
                    sb.append(" Experience is also significantly short: ").append(yoe)
                      .append(" years against a ").append(jdRange).append(" requirement.");
                }
                if (!titleOk) {
                    sb.append(" The title direction is different from what this role calls for.");
                }
                sb.append(" This is a fit problem, not a presentation problem — applying as-is is unlikely to convert. Consider a closer-fit role or repositioning toward the missing requirements.");
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
        // Issue 4: don't store generic example text as beforeAfter.before — it's not the candidate's text
        // beforeAfter is only set by AiReviewService when it has real resume text to inject
        f.setImpact(impact);
        return f;
    }

    /** Convenience factory — mirrors the old positional Signal constructor signature. */
    private static Signal signal(String id, String label, SignalStatus status, SignalFriction friction,
                                  String observation, String interpretation, ImpactLevel impact) {
        return signal(id, label, status, friction, observation, interpretation, impact, null);
    }

    private static Signal signal(String id, String label, SignalStatus status, SignalFriction friction,
                                  String observation, String interpretation, ImpactLevel impact, String evidence) {
        Signal s = new Signal();
        s.setId(id);
        s.setLabel(label);
        s.setStatus(status);
        s.setFriction(friction);
        s.setObservation(observation);
        s.setInterpretation(interpretation);
        s.setImpact(impact);
        s.setEvidence(evidence);
        // Confidence: PASS signals are HIGH, WARN are MEDIUM, FAIL are HIGH (we're confident it's a problem)
        s.setConfidence(status == SignalStatus.WARN ? Confidence.MEDIUM : Confidence.HIGH);
        return s;
    }

    // ── Output record ─────────────────────────────────────────────────────────

    public record FeedbackOutput(List<Signal> signals, List<Fix> fixes, String summaryLine) {}
}
