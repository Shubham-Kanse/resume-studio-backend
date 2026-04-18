package com.resumestudio.reviewer.ats;

import com.resumestudio.reviewer.extraction.JdParserService;
import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import com.resumestudio.reviewer.nlp.BulletEnricher;
import com.resumestudio.reviewer.signals.CoherenceEngine;
import com.resumestudio.reviewer.signals.ResumeScoreCalculator;
import com.resumestudio.reviewer.signals.SignalComputationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import static com.resumestudio.reviewer.ats.BulletQualityScorer.*;

/**
 * Builds the full AtsScoreResponse from a parsed Resume + raw text.
 *
 * Resume-only mode: runs all local scorers (BulletQuality, Brevity, Style, Buzzwords).
 * JD mode: additionally runs the full SignalComputationService pipeline to produce
 *   real keyword match, targetRoleScore, keywordAnalysis, coherence flags, and
 *   evidenceSummary.advancedSignals.
 */
@Service
public class AtsResponseBuilder {

    private static final Logger log = LoggerFactory.getLogger(AtsResponseBuilder.class);

    private final BulletQualityScorer bulletScorer;
    private final BrevityScorer brevityScorer;
    private final StyleScorer styleScorer;
    private final BuzzwordsScorer buzzwordsScorer;
    private final JdParserService jdParser;
    private final SignalComputationService signalService;
    private final ResumeScoreCalculator scoreCalculator;
    private final CoherenceEngine coherenceEngine;
    private final BulletEnricher bulletEnricher;
    private final AtsSectionAdviceService adviceService;

    public AtsResponseBuilder(BulletQualityScorer bulletScorer,
                               BrevityScorer brevityScorer,
                               StyleScorer styleScorer,
                               BuzzwordsScorer buzzwordsScorer,
                               JdParserService jdParser,
                               SignalComputationService signalService,
                               ResumeScoreCalculator scoreCalculator,
                               CoherenceEngine coherenceEngine,
                               BulletEnricher bulletEnricher,
                               AtsSectionAdviceService adviceService) {
        this.bulletScorer = bulletScorer;
        this.brevityScorer = brevityScorer;
        this.styleScorer = styleScorer;
        this.buzzwordsScorer = buzzwordsScorer;
        this.jdParser = jdParser;
        this.signalService = signalService;
        this.scoreCalculator = scoreCalculator;
        this.coherenceEngine = coherenceEngine;
        this.bulletEnricher = bulletEnricher;
        this.adviceService = adviceService;
    }

    public AtsScoreResponse build(Resume resume, String rawText, String jobDescription) {
        List<String> bullets = BulletQualityScorer.extractBullets(
            resume.getExperience() != null ? resume.getExperience() : List.of());

        // ── Local scorers (always run) ────────────────────────────────────────
        AtsReport.AtsSection quantifyingImpact = bulletScorer.scoreQuantifyingImpact(bullets);
        AtsReport.AtsSection actionVerbUse     = bulletScorer.scoreActionVerbUse(bullets);
        AtsReport.AtsSection accomplishments   = bulletScorer.scoreAccomplishments(bullets);
        AtsReport.AtsSection repetition        = bulletScorer.scoreRepetition(bullets);
        AtsReport.AtsSection length            = brevityScorer.scoreLength(rawText);
        AtsReport.AtsSection fillerWords       = brevityScorer.scoreFillerWords(bullets);
        AtsReport.AtsSection totalBullets      = brevityScorer.scoreTotalBulletPoints(bullets);
        AtsReport.AtsSection bulletLength      = brevityScorer.scoreBulletPointsLength(bullets);
        AtsReport.AtsSection sections          = styleScorer.scoreSections(resume);
        AtsReport.AtsSection pronouns          = styleScorer.scorePersonalPronouns(rawText);
        AtsReport.AtsSection buzzwords         = buzzwordsScorer.scoreBuzzwords(rawText);
        AtsReport.AtsSection activeVoice       = styleScorer.scoreActiveVoice(bullets);
        AtsReport.AtsSection consistency       = styleScorer.scoreConsistency(bullets, rawText);
        AtsReport.AtsSection dateOrder         = styleScorer.scoreDateOrder(
            resume.getExperience() != null ? resume.getExperience() : List.of());
        AtsReport.AtsSection spellCheck        = styleScorer.scoreSpellCheck(rawText);

        // ── Weighted group scores ─────────────────────────────────────────────
        int impactScore  = weightedAvg(
            new int[]{quantifyingImpact.score(), actionVerbUse.score(), accomplishments.score(), repetition.score()},
            new int[]{12, 10, 10, 8});
        int brevityScore = weightedAvg(
            new int[]{length.score(), fillerWords.score(), totalBullets.score(), bulletLength.score()},
            new int[]{7, 6, 6, 6});
        // spellCheck included: misspelled tech keywords are invisible to ATS parsers
        int styleScore   = weightedAvg(
            new int[]{sections.score(), pronouns.score(), buzzwords.score(), activeVoice.score(), consistency.score(), dateOrder.score(), spellCheck.score()},
            new int[]{8, 5, 6, 6, 5, 5, 5});

        int resumeQualityScore = clamp((int) Math.round((impactScore * 40.0 + brevityScore * 25.0 + styleScore * 35.0) / 100.0));

        // ── ATS compatibility ─────────────────────────────────────────────────
        List<String> parseIssues = new ArrayList<>();
        List<String> parseWarnings = new ArrayList<>();
        if (resume.isMultiColumn()) parseIssues.add("Multi-column layout detected — ATS parsers may misread column order.");
        if (resume.isHasPhoto()) parseWarnings.add("Photo detected — remove for ATS submissions.");
        if (resume.getParseConfidence() < 0.5) parseWarnings.add("Low parse confidence — consider a simpler format.");
        int parseability = clamp(100 - parseIssues.size() * 15 - parseWarnings.size() * 8);

        // ── JD mode: run full signal pipeline ────────────────────────────────
        boolean hasJd = jobDescription != null && !jobDescription.isBlank();
        ResumeSignals signals = null;
        ResumeScore jdScore = null;
        CoherenceEngine.CoherenceResult coherence = null;
        JobDescription parsedJd = null;

        if (hasJd) {
            try {
                parsedJd = jdParser.parse(jobDescription);
                RawDocument raw = RawDocument.fromText(rawText);
                signals = signalService.compute(resume, parsedJd, raw);
                jdScore = scoreCalculator.calculate(signals);
                coherence = coherenceEngine.check(signals);
            } catch (Exception e) {
                log.warn("Signal pipeline failed in JD mode — falling back to resume-only scoring: {}", e.getMessage());
            }
        }

        // ── Bullet enrichment (always run — feeds BulletIntelligence) ─────────
        List<String> jdMustHaves = parsedJd != null ? parsedJd.getMustHaveSkills() : List.of();
        BulletEnricher.EnrichmentResult enrichment = null;
        try {
            enrichment = bulletEnricher.enrich(
                resume.getExperience() != null ? resume.getExperience() : List.of(),
                jdMustHaves);
        } catch (Exception e) {
            log.warn("BulletEnricher failed — omitting bulletIntelligence: {}", e.getMessage());
        }

        // ── Scores ────────────────────────────────────────────────────────────
        int overallScore = resumeQualityScore;
        Integer targetRoleScore = null;
        if (jdScore != null) {
            targetRoleScore = jdScore.getComposite();
            // Blend: 60% JD-aware pipeline score + 40% local quality score
            overallScore = clamp((int) Math.round((targetRoleScore * 60.0 + resumeQualityScore * 40.0) / 100.0));
        }

        // ── Category scores ───────────────────────────────────────────────────
        int contentQualityScore = weightedAvg(
            new int[]{quantifyingImpact.score(), actionVerbUse.score(), accomplishments.score()},
            new int[]{4, 3, 3});
        int formattingScore = weightedAvg(
            new int[]{sections.score(), consistency.score(), dateOrder.score()},
            new int[]{4, 3, 3});
        int summaryScore = scoreSummary(resume);
        int skillsScore  = scoreSkills(resume);

        AtsScoreResponse.ScorePair keywordMatchPair = null;
        if (signals != null && signals.getMustHaveResults() != null && !signals.getMustHaveResults().isEmpty()) {
            long found = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() != SkillVisibility.MISSING).count();
            int kw = clamp((int)(found * 100 / signals.getMustHaveResults().size()));
            keywordMatchPair = new AtsScoreResponse.ScorePair(kw * 30 / 100, 30);
        }

        AtsScoreResponse.CategoryScores categoryScores = new AtsScoreResponse.CategoryScores(
            keywordMatchPair,
            new AtsScoreResponse.ScorePair(formattingScore * 20 / 100, 20),
            new AtsScoreResponse.ScorePair(contentQualityScore * 25 / 100, 25),
            new AtsScoreResponse.ScorePair(summaryScore * 10 / 100, 10),
            new AtsScoreResponse.ScorePair(skillsScore * 15 / 100, 15),
            null);

        // ── Keyword analysis (JD mode only) ───────────────────────────────────
        Object keywordAnalysis = buildKeywordAnalysis(signals, parsedJd, rawText, resume);

        // ── Key findings ──────────────────────────────────────────────────────
        List<String> presentKeywords = null, missingKeywords = null;
        if (signals != null && signals.getMustHaveResults() != null) {
            presentKeywords = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() != SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill).collect(Collectors.toList());
            missingKeywords = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill).collect(Collectors.toList());
        }
        List<String> strengths  = buildStrengths(quantifyingImpact, actionVerbUse, sections, spellCheck, resume, signals);
        List<String> weaknesses = buildWeaknesses(quantifyingImpact, actionVerbUse, fillerWords, buzzwords, sections, signals);

        // ── Section reviews ───────────────────────────────────────────────────
        List<AtsScoreResponse.AtsSectionReview> sectionReviews = buildSectionReviews(
            resume, bullets, quantifyingImpact, actionVerbUse, sections, skillsScore, summaryScore,
            signals, parsedJd);

        // ── Detailed issues ───────────────────────────────────────────────────
        List<AtsScoreResponse.AtsIssue> detailedIssues = buildDetailedIssues(
            quantifyingImpact, actionVerbUse, accomplishments, repetition,
            fillerWords, buzzwords, sections, pronouns, activeVoice, consistency, dateOrder, spellCheck,
            signals, coherence);

        // ── Recommendations ───────────────────────────────────────────────────
        List<AtsScoreResponse.AtsRecommendation> recommendations = buildRecommendations(
            overallScore, quantifyingImpact, actionVerbUse, sections, fillerWords, buzzwords, signals);

        // ── Debug analysis ────────────────────────────────────────────────────
        List<AtsScoreResponse.AtsDebugSection> debugAnalysis = buildDebugAnalysis(
            bullets, quantifyingImpact, actionVerbUse, accomplishments, repetition,
            length, fillerWords, totalBullets, bulletLength,
            sections, pronouns, buzzwords, activeVoice, consistency, dateOrder, spellCheck);

        // ── Evidence summary ──────────────────────────────────────────────────
        AtsScoreResponse.EvidenceSummary evidenceSummary = buildEvidenceSummary(
            resume, parseIssues, parseWarnings, signals, coherence, parsedJd);

        // ── Advanced insights ─────────────────────────────────────────────────
        AtsScoreResponse.AdvancedInsights advancedInsights = buildAdvancedInsights(
            overallScore, resume, bullets, signals, coherence, hasJd);

        // ── Assemble ──────────────────────────────────────────────────────────
        AtsScoreResponse response = new AtsScoreResponse();
        response.analysisMode          = hasJd ? "resume-with-jd" : "resume-only";
        response.resumeQualityScore    = resumeQualityScore;
        response.standaloneResumeScore = resumeQualityScore;
        response.targetRoleScore       = targetRoleScore;
        response.overallScore          = overallScore;
        response.impactScore           = impactScore;
        response.brevityScore          = brevityScore;
        response.styleScore            = styleScore;
        response.categoryScores        = categoryScores;
        response.rating                = toRating(overallScore);
        response.keyFindings           = new AtsScoreResponse.KeyFindings(strengths, weaknesses, missingKeywords, presentKeywords);
        response.detailedIssues        = detailedIssues;
        response.recommendations       = recommendations;
        response.sectionReviews        = sectionReviews;
        response.atsCompatibility      = new AtsScoreResponse.AtsCompatibility(parseability, parseIssues, parseWarnings, null, null);
        response.keywordAnalysis       = keywordAnalysis;
        response.evidenceSummary       = evidenceSummary;
        response.debugAnalysis         = debugAnalysis;
        response.qualitySections       = buildQualitySections(bullets,
            quantifyingImpact, actionVerbUse, accomplishments, repetition,
            length, fillerWords, totalBullets, bulletLength,
            sections, pronouns, buzzwords, activeVoice, consistency, dateOrder, spellCheck);
        response.advancedInsights      = advancedInsights;
        response.bulletIntelligence    = buildBulletIntelligence(enrichment);
        response.skillIntelligence     = buildSkillIntelligence(signals, parsedJd, rawText);
        response.coherenceReport       = buildCoherenceReport(coherence);
        response.seniorityCalibration  = buildSeniorityCalibration(signals, resume, bullets);
        return response;
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    static int weightedAvg(int[] scores, int[] weights) {
        int totalWeight = 0, weightedSum = 0;
        for (int i = 0; i < scores.length; i++) { weightedSum += scores[i] * weights[i]; totalWeight += weights[i]; }
        return totalWeight == 0 ? 0 : clamp((int) Math.round((double) weightedSum / totalWeight));
    }

    private static String toRating(int score) {
        if (score >= 90) return "Excellent";
        if (score >= 80) return "Very Good";
        if (score >= 70) return "Good";
        if (score >= 60) return "Fair";
        return "Poor";
    }

    static String toStatus(int score) {
        if (score >= 80) return "strong";
        if (score >= 65) return "good";
        if (score >= 45) return "needs-work";
        return "weak";
    }

    private int scoreSummary(Resume resume) {
        if (resume.getSummaryText() == null || resume.getSummaryText().isBlank()) return 20;
        String s = resume.getSummaryText();
        int score = 50;
        if (s.split("\\s+").length >= 30) score += 15;
        if (ACHIEVEMENT_METRIC.matcher(s).find()) score += 20;
        if (resume.getCurrentTitle() != null && s.toLowerCase().contains(resume.getCurrentTitle().toLowerCase())) score += 15;
        return clamp(score);
    }

    private int scoreSkills(Resume resume) {
        if (resume.getSkills() == null || resume.getSkills().isEmpty()) return 20;
        int count = resume.getSkills().size();
        if (count >= 8 && count <= 20) return 100;
        if (count >= 5) return 80;
        if (count >= 3) return 60;
        return 40;
    }

    // ── Keyword analysis ──────────────────────────────────────────────────────

    private Object buildKeywordAnalysis(ResumeSignals signals, JobDescription jd, String rawText, Resume resume) {
        if (signals == null || jd == null || signals.getMustHaveResults() == null) return null;

        List<SkillMatchResult> results = signals.getMustHaveResults();
        if (results.isEmpty()) return null; // no keywords extracted from JD — don't show 0/0

        long matched = results.stream().filter(r -> r.getVisibility() != SkillVisibility.MISSING).count();
        int total = results.size();
        double matchPct = total > 0 ? (double) matched / total * 100 : 0;

        List<String> presentKw = results.stream()
            .filter(r -> r.getVisibility() != SkillVisibility.MISSING)
            .map(SkillMatchResult::getJdSkill).collect(Collectors.toList());
        List<String> missingKw = results.stream()
            .filter(r -> r.getVisibility() == SkillVisibility.MISSING)
            .map(SkillMatchResult::getJdSkill).collect(Collectors.toList());

        // Title-matched: skills that appear in the candidate's current title
        String titleLower = resume.getCurrentTitle() != null ? resume.getCurrentTitle().toLowerCase() : "";
        List<String> titleMatched = presentKw.stream()
            .filter(kw -> titleLower.contains(kw.toLowerCase()))
            .collect(Collectors.toList());

        String resumeLower = rawText.toLowerCase();
        List<String> overused = presentKw.stream()
            .filter(kw -> countOccurrences(resumeLower, kw.toLowerCase()) >= 6)
            .collect(Collectors.toList());
        List<String> underused = presentKw.stream()
            .filter(kw -> countOccurrences(resumeLower, kw.toLowerCase()) == 1)
            .collect(Collectors.toList());

        int resumeWords = rawText.split("\\s+").length;
        double density = resumeWords > 0 ? (double) matched / resumeWords * 100 : 0;

        // Per-section coverage — which matched keywords appear in which section
        String summaryText = resume.getSummaryText() != null ? resume.getSummaryText().toLowerCase() : "";
        String skillsText  = resume.getSkills() != null ? resume.getSkills().stream()
            .map(s -> s.getRawName() != null ? s.getRawName().toLowerCase() : "").collect(Collectors.joining(" ")) : "";
        String expText = resume.getExperience() != null ? resume.getExperience().stream()
            .flatMap(e -> e.getBullets() != null ? e.getBullets().stream() : java.util.stream.Stream.of())
            .collect(Collectors.joining(" ")).toLowerCase() : "";

        Map<String, Object> ka = new LinkedHashMap<>();
        ka.put("totalKeywordsInJD", total);
        ka.put("matchedKeywords", (int) matched);
        ka.put("matchPercentage", Math.round(matchPct * 10.0) / 10.0);
        ka.put("keywordDensity", Math.round(density * 100.0) / 100.0);
        ka.put("overusedKeywords", overused);
        ka.put("underusedKeywords", underused);
        ka.put("matchedByCategory", Map.of(
            "title",    titleMatched,
            "required", presentKw,
            "preferred", List.of(),
            "culture",  List.of()));
        ka.put("missingByCategory", Map.of(
            "required",  missingKw,
            "preferred", List.of()));
        ka.put("coverageBySection", Map.of(
            "professionalSummary", presentKw.stream().filter(k -> summaryText.contains(k.toLowerCase())).collect(Collectors.toList()),
            "skills",              presentKw.stream().filter(k -> skillsText.contains(k.toLowerCase())).collect(Collectors.toList()),
            "workExperience",      presentKw.stream().filter(k -> expText.contains(k.toLowerCase())).collect(Collectors.toList()),
            "education",           List.of(),
            "projects",            List.of(),
            "certifications",      List.of()));
        return ka;
    }

    private int countOccurrences(String text, String phrase) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(phrase, idx)) != -1) { count++; idx += phrase.length(); }
        return count;
    }

    // ── Key findings ──────────────────────────────────────────────────────────

    private List<String> buildStrengths(AtsReport.AtsSection qi, AtsReport.AtsSection av,
                                         AtsReport.AtsSection sec, AtsReport.AtsSection spell,
                                         Resume resume, ResumeSignals signals) {
        List<String> s = new ArrayList<>();
        if (qi.score() >= 70) s.add("Strong use of metrics and quantified achievements.");
        if (av.score() >= 70) s.add("Bullets open with impactful action verbs.");
        if (sec.score() >= 80) s.add("All required resume sections are present.");
        if (spell.score() == 100) s.add("No spelling errors detected.");
        if (resume.getSkills() != null && resume.getSkills().size() >= 8) s.add("Comprehensive skills section.");
        if (resume.getSummaryText() != null && !resume.getSummaryText().isBlank()) s.add("Professional summary is present.");
        if (signals != null && signals.isAllMustHavesFound()
                && signals.getMustHaveResults() != null && !signals.getMustHaveResults().isEmpty())
            s.add("All required JD skills are present on the resume.");
        if (signals != null && signals.getCalculatedYoe() != null)
            s.add(String.format("%.0f years of experience calculated from work history.", signals.getCalculatedYoe()));
        return s.isEmpty() ? List.of("Resume has been successfully parsed and analyzed.") : s.subList(0, Math.min(s.size(), 5));
    }

    private List<String> buildWeaknesses(AtsReport.AtsSection qi, AtsReport.AtsSection av,
                                          AtsReport.AtsSection fw, AtsReport.AtsSection bw,
                                          AtsReport.AtsSection sec, ResumeSignals signals) {
        List<String> w = new ArrayList<>();
        if (qi.score() < 60) w.add("Many bullets lack measurable outcomes or metrics.");
        if (av.score() < 60) w.add("Weak or passive verb openings reduce ATS impact score.");
        if (fw.score() < 70) w.add("Filler phrases dilute the strength of your experience.");
        if (bw.score() < 70) w.add("Generic buzzwords reduce credibility with recruiters.");
        if (sec.score() < 80) w.add("One or more required sections are missing.");
        if (signals != null && signals.isHasMissingMustHaves())
            w.add("Missing required JD skills: " + String.join(", ", signals.getMissingMustHavesList().stream().limit(3).toList()) + ".");
        if (signals != null && signals.isHasSkillAgeMismatch() && signals.getSkillAgeMismatchDetail() != null)
            w.add("Skill age anomaly: " + signals.getSkillAgeMismatchDetail());
        if (signals != null && signals.isHasTitleInflation())
            w.add("Title inflation detected — ensure your title matches your demonstrated experience level.");
        return w.isEmpty() ? List.of("Minor improvements possible — see detailed sections.") : w.subList(0, Math.min(w.size(), 5));
    }

    // ── Section reviews ───────────────────────────────────────────────────────

    private List<AtsScoreResponse.AtsSectionReview> buildSectionReviews(
            Resume resume, List<String> bullets,
            AtsReport.AtsSection qi, AtsReport.AtsSection av,
            AtsReport.AtsSection sec, int skillsScore, int summaryScore,
            ResumeSignals signals, JobDescription jd) {

        List<AtsScoreResponse.AtsSectionReview> reviews = new ArrayList<>();

        // Professional Summary
        reviews.add(new AtsScoreResponse.AtsSectionReview(
            "professionalSummary", "Professional Summary", summaryScore, toStatus(summaryScore),
            resume.getSummaryText() == null || resume.getSummaryText().isBlank()
                ? "No professional summary detected. This is a critical ATS section."
                : "Summary is present. Ensure it includes your title, years of experience, and a key metric.",
            resume.getSummaryText() != null && !resume.getSummaryText().isBlank()
                ? List.of("Summary section is present.") : List.of(),
            resume.getSummaryText() == null || resume.getSummaryText().isBlank()
                ? List.of("Missing professional summary.") : List.of(),
            resume.getSummaryText() == null || resume.getSummaryText().isBlank()
                ? List.of("Add a 3–4 line summary with your title, years of experience, and one quantified achievement.")
                : List.of("Include 3–5 keywords from the job description.", "Add at least one quantified achievement.")));

        // Work Experience
        int expScore = weightedAvg(new int[]{qi.score(), av.score()}, new int[]{6, 4});
        reviews.add(new AtsScoreResponse.AtsSectionReview(
            "workExperience", "Work Experience", expScore, toStatus(expScore),
            resume.getExperience() == null || resume.getExperience().isEmpty()
                ? "No work experience section detected."
                : "Experience section found with " + bullets.size() + " bullets.",
            expScore >= 70 ? List.of("Experience section is present with structured bullets.") : List.of(),
            expScore < 60 ? List.of("Bullets lack quantified outcomes.", "Weak verb openings detected.") : List.of(),
            List.of("Use STAR format: Situation, Task, Action, Result.",
                    "Add metrics to at least 60% of bullets.",
                    "Open every bullet with a strong action verb.")));

        // Skills
        reviews.add(new AtsScoreResponse.AtsSectionReview(
            "skills", "Skills", skillsScore, toStatus(skillsScore),
            resume.getSkills() == null || resume.getSkills().isEmpty()
                ? "No skills section detected."
                : resume.getSkills().size() + " skills extracted."
                  + (signals != null && signals.isHasMissingMustHaves()
                     ? " Missing JD skills: " + String.join(", ", signals.getMissingMustHavesList().stream().limit(3).toList()) + "."
                     : ""),
            skillsScore >= 70 ? List.of("Skills section is present.") : List.of(),
            skillsScore < 60 ? List.of("Skills section is thin or missing.") : List.of(),
            List.of("List 8–20 skills matching the job description.",
                    "Group by category: Languages, Frameworks, Tools, Methodologies.")));

        // Education
        int eduScore = resume.getEducation() != null && !resume.getEducation().isEmpty() ? 90 : 30;
        reviews.add(new AtsScoreResponse.AtsSectionReview(
            "education", "Education", eduScore, toStatus(eduScore),
            resume.getEducation() == null || resume.getEducation().isEmpty()
                ? "No education section detected." : "Education section present.",
            eduScore >= 70 ? List.of("Education section is present.") : List.of(),
            eduScore < 60 ? List.of("Education section is missing.") : List.of(),
            List.of("Include degree, institution, and graduation year.", "Add relevant coursework if early-career.")));

        // Keywords — real data in JD mode
        if (signals != null && signals.getMustHaveResults() != null && !signals.getMustHaveResults().isEmpty()) {
            long found = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() != SkillVisibility.MISSING).count();
            int total = signals.getMustHaveResults().size();
            int kwScore = clamp((int)(found * 100 / total));
            List<String> missing = signals.getMissingMustHavesList().stream().limit(5).toList();
            reviews.add(new AtsScoreResponse.AtsSectionReview(
                "keywords", "Keywords", kwScore, toStatus(kwScore),
                found + " of " + total + " required JD skills found on resume.",
                signals.getMustHaveResults().stream()
                    .filter(r -> r.getVisibility() != SkillVisibility.MISSING)
                    .map(SkillMatchResult::getJdSkill).limit(5).collect(Collectors.toList()),
                missing,
                missing.isEmpty() ? List.of("All required keywords are present.")
                    : List.of("Add missing keywords naturally in your experience bullets.",
                              "Ensure exact terminology from the JD is used.")));
        } else if (signals != null) {
            // JD was provided but the parser extracted no must-have skills from it
            reviews.add(new AtsScoreResponse.AtsSectionReview(
                "keywords", "Keywords", 0, "info",
                "No required skills could be extracted from the job description.",
                List.of(),
                List.of("The JD did not contain a clear requirements section."),
                List.of("Use a JD with explicit skill requirements (e.g. 'Requirements:', 'Must have:').",
                        "Paste the raw JD text rather than a URL if possible.")));
        } else {
            // No JD provided at all
            reviews.add(new AtsScoreResponse.AtsSectionReview(
                "keywords", "Keywords", 0, "needs-work",
                "Keyword analysis requires a job description. Paste a JD to enable this section.",
                List.of(), List.of("No job description provided."),
                List.of("Paste a job description to see keyword match analysis.")));
        }

        // Formatting
        int fmtScore = sec.score();
        reviews.add(new AtsScoreResponse.AtsSectionReview(
            "formatting", "Formatting", fmtScore, toStatus(fmtScore),
            "Formatting analysis based on section structure and consistency.",
            fmtScore >= 70 ? List.of("Section headers are ATS-safe.") : List.of(),
            sec.issues(),
            sec.suggestions().isEmpty()
                ? List.of("Use standard section headers.", "Avoid tables, columns, and text boxes.")
                : sec.suggestions()));

        return reviews;
    }

    // ── Detailed issues ───────────────────────────────────────────────────────

    private List<AtsScoreResponse.AtsIssue> buildDetailedIssues(
            AtsReport.AtsSection qi, AtsReport.AtsSection av, AtsReport.AtsSection acc,
            AtsReport.AtsSection rep, AtsReport.AtsSection fw, AtsReport.AtsSection bw,
            AtsReport.AtsSection sec, AtsReport.AtsSection pro, AtsReport.AtsSection act,
            AtsReport.AtsSection con, AtsReport.AtsSection dat, AtsReport.AtsSection sp,
            ResumeSignals signals, CoherenceEngine.CoherenceResult coherence) {

        List<AtsScoreResponse.AtsIssue> issues = new ArrayList<>();
        for (AtsReport.AtsSection s : new AtsReport.AtsSection[]{qi, av, acc, rep, fw, bw, sec, pro, act, con, dat, sp}) {
            if (s.issues().isEmpty()) continue;
            String severity = s.score() < 40 ? "critical" : s.score() < 60 ? "high" : s.score() < 75 ? "medium" : "low";
            String category = labelToCategory(s.label());
            issues.add(new AtsScoreResponse.AtsIssue(severity, category,
                s.label() + " needs improvement",
                "Affects your " + category.toLowerCase() + " score.",
                s.suggestions().isEmpty() ? "Review and improve this section." : s.suggestions().get(0),
                s.issues().get(0)));
        }

        // Coherence flags from the signal pipeline
        if (coherence != null) {
            for (CoherenceEngine.CoherenceFlag flag : coherence.flags()) {
                String sev = switch (flag.severity()) {
                    case HIGH -> "high"; case MEDIUM -> "medium"; default -> "low";
                };
                issues.add(new AtsScoreResponse.AtsIssue(sev, "Credibility",
                    flag.type().replace("_", " ").toLowerCase(),
                    flag.detail(), "Review and address this inconsistency.", flag.detail()));
            }
        }

        // Anomaly signals
        if (signals != null && signals.isHasSkillAgeMismatch() && signals.getSkillAgeMismatchDetail() != null) {
            issues.add(new AtsScoreResponse.AtsIssue("medium", "Credibility",
                "Skill age mismatch detected", signals.getSkillAgeMismatchDetail(),
                "Verify your claimed years of experience for each technology.", signals.getSkillAgeMismatchDetail()));
        }

        issues.sort(Comparator.comparingInt(i -> severityOrder(i.severity())));
        return issues.subList(0, Math.min(issues.size(), 8));
    }

    private static int severityOrder(String s) {
        return switch (s) { case "critical" -> 0; case "high" -> 1; case "medium" -> 2; default -> 3; };
    }

    private static String labelToCategory(String label) {
        return switch (label) {
            case "Quantifying Impact", "Action Verb Use", "Accomplishments", "Repetition" -> "Impact";
            case "Length", "Filler Words", "Total Bullet Points", "Bullet Points Length" -> "Brevity";
            default -> "Style";
        };
    }

    // ── Recommendations ───────────────────────────────────────────────────────

    private List<AtsScoreResponse.AtsRecommendation> buildRecommendations(
            int overall, AtsReport.AtsSection qi, AtsReport.AtsSection av,
            AtsReport.AtsSection sec, AtsReport.AtsSection fw, AtsReport.AtsSection bw,
            ResumeSignals signals) {
        List<AtsScoreResponse.AtsRecommendation> recs = new ArrayList<>();
        if (qi.score() < 65) recs.add(new AtsScoreResponse.AtsRecommendation("high",
            "Add metrics to your experience bullets",
            "Quantified bullets score 40% higher in ATS systems.",
            "Add numbers, percentages, or scale to at least 60% of your bullets. E.g. 'Reduced load time by 45%'."));
        if (av.score() < 65) recs.add(new AtsScoreResponse.AtsRecommendation("high",
            "Replace weak verb openings",
            "Strong action verbs signal ownership and impact.",
            "Start every bullet with a verb like 'Built', 'Led', 'Reduced', 'Delivered'. Remove 'Responsible for'."));
        if (sec.score() < 80) recs.add(new AtsScoreResponse.AtsRecommendation("high",
            "Add missing resume sections",
            "Missing sections cause ATS rejection before human review.",
            "Ensure you have: Professional Summary, Work Experience, Skills, Education."));
        if (fw.score() < 70) recs.add(new AtsScoreResponse.AtsRecommendation("medium",
            "Remove filler phrases",
            "Filler language reduces keyword density and credibility.",
            "Delete phrases like 'responsible for', 'helped with', 'worked on'. Start with the action."));
        if (bw.score() < 70) recs.add(new AtsScoreResponse.AtsRecommendation("medium",
            "Replace buzzwords with evidence",
            "Generic phrases are ignored by ATS and recruiters.",
            "Instead of 'results-driven', show a result. Instead of 'team player', describe a collaboration outcome."));
        if (signals != null && signals.isHasMissingMustHaves() && !signals.getMissingMustHavesList().isEmpty())
            recs.add(new AtsScoreResponse.AtsRecommendation("high",
                "Add missing required skills: " + String.join(", ", signals.getMissingMustHavesList().stream().limit(3).toList()),
                "Missing required skills cause automatic ATS rejection.",
                "Add these skills to your skills section and demonstrate them in experience bullets."));
        if (overall >= 75) recs.add(new AtsScoreResponse.AtsRecommendation("low",
            "Tailor resume to each job description",
            "Keyword-matched resumes pass ATS at 3× the rate of generic ones.",
            "Paste a job description into the ATS scorer to see exact keyword gaps."));
        return recs.subList(0, Math.min(recs.size(), 6));
    }

    // ── Debug analysis — with "bullets" section for frontend lookups ──────────

    private List<AtsScoreResponse.AtsDebugSection> buildDebugAnalysis(
            List<String> bullets,
            AtsReport.AtsSection qi, AtsReport.AtsSection av, AtsReport.AtsSection acc,
            AtsReport.AtsSection rep, AtsReport.AtsSection len, AtsReport.AtsSection fw,
            AtsReport.AtsSection tb, AtsReport.AtsSection bl,
            AtsReport.AtsSection sec, AtsReport.AtsSection pro, AtsReport.AtsSection bw,
            AtsReport.AtsSection act, AtsReport.AtsSection con, AtsReport.AtsSection dat,
            AtsReport.AtsSection sp) {

        List<AtsScoreResponse.AtsDebugSection> debug = new ArrayList<>();

        // "bullets" section — required by frontend findDebugItem() calls
        long quantifiedCount = bullets.stream()
            .filter(b -> ACHIEVEMENT_METRIC.matcher(b).find()).count();
        debug.add(new AtsScoreResponse.AtsDebugSection("bullets", "Bullet Analysis",
            "Bullet-level quality signals",
            List.of(
                new AtsScoreResponse.AtsDebugItem(
                    "Quantified bullets",
                    quantifiedCount + "/" + bullets.size(),
                    quantifiedCount < bullets.size() * 0.6 ? "Add metrics to more bullets." : null,
                    quantifiedCount >= bullets.size() * 0.6 ? "good" : "warning"),
                new AtsScoreResponse.AtsDebugItem(
                    "Business impact bullets",
                    String.valueOf(acc.score()),
                    acc.suggestions().isEmpty() ? null : acc.suggestions().get(0),
                    acc.score() >= 70 ? "good" : "warning"),
                new AtsScoreResponse.AtsDebugItem(
                    "Average bullet length",
                    bullets.isEmpty() ? "0 words" : String.format("%.0f words avg",
                        bullets.stream().mapToInt(b -> b.split("\\s+").length).average().orElse(0)),
                    null, "info")
            )));

        // Impact group — weights match the top-level impactScore formula
        debug.add(debugGroup("impact", "Impact",
            List.of(qi, av, acc, rep),
            new int[]{12, 10, 10, 8}));

        // Brevity group — weights match the top-level brevityScore formula
        debug.add(debugGroup("brevity", "Brevity",
            List.of(len, fw, tb, bl),
            new int[]{7, 6, 6, 6}));

        // Style group — weights match the top-level styleScore formula (spellCheck included)
        debug.add(debugGroup("style", "Style",
            List.of(sec, pro, bw, act, con, dat, sp),
            new int[]{8, 5, 6, 6, 5, 5, 5}));

        return debug;
    }

    private AtsScoreResponse.AtsDebugSection debugGroup(String id, String title,
                                                          List<AtsReport.AtsSection> sections,
                                                          int[] weights) {
        List<AtsScoreResponse.AtsDebugItem> items = sections.stream().map(s -> {
            String sev = s.score() >= 80 ? "good" : s.score() >= 55 ? "info" : s.score() >= 40 ? "warning" : "critical";
            String detail = s.issues().isEmpty() ? "No issues found." : String.join(" | ", s.issues());
            String suggestion = s.suggestions().isEmpty() ? null : s.suggestions().get(0);
            return new AtsScoreResponse.AtsDebugItem(s.label() + " (" + s.score() + ")", detail, suggestion, sev);
        }).collect(Collectors.toList());
        int groupScore = weightedAvg(
            sections.stream().mapToInt(AtsReport.AtsSection::score).toArray(), weights);
        return new AtsScoreResponse.AtsDebugSection(id, title, title + " group score: " + groupScore + "/100", items);
    }

    // ── Evidence summary ──────────────────────────────────────────────────────

    private AtsScoreResponse.EvidenceSummary buildEvidenceSummary(
            Resume resume, List<String> parseIssues, List<String> parseWarnings,
            ResumeSignals signals, CoherenceEngine.CoherenceResult coherence,
            JobDescription jd) {

        List<String> present = new ArrayList<>(), missing = new ArrayList<>();
        if (resume.getSummaryText() != null && !resume.getSummaryText().isBlank()) present.add("professionalSummary"); else missing.add("professionalSummary");
        if (resume.getExperience() != null && !resume.getExperience().isEmpty()) present.add("workExperience"); else missing.add("workExperience");
        if (resume.getSkills() != null && !resume.getSkills().isEmpty()) present.add("skills"); else missing.add("skills");
        if (resume.getEducation() != null && !resume.getEducation().isEmpty()) present.add("education"); else missing.add("education");

        Double yoe = signals != null && signals.getCalculatedYoe() != null
            ? signals.getCalculatedYoe() : resume.getTotalYoeYears();
        Double yoeMin = jd != null ? jd.getYoeMin() : null;
        Boolean meetsYoe = (yoe != null && yoeMin != null) ? yoe >= yoeMin : null;

        List<String> matchedKw = signals != null && signals.getMustHaveResults() != null
            ? signals.getMustHaveResults().stream().filter(r -> r.getVisibility() != SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill).collect(Collectors.toList()) : List.of();
        List<String> missingKw = signals != null ? signals.getMissingMustHavesList() : List.of();

        // Advanced signals from coherence + signal pipeline
        AtsScoreResponse.AdvancedSignals advancedSignals = null;
        if (signals != null) {
            int tenseIssues = 0;
            int terminologyDrift = coherence != null
                ? (int) coherence.flags().stream().filter(f -> f.type().contains("TITLE")).count() : 0;
            int uncoveredSkills = signals.getShallowSkills() != null ? signals.getShallowSkills().size() : 0;
            int weakMetrics = (int)((1 - signals.getMetricDensity()) * 10);
            int strongMetrics = (int)(signals.getMetricDensity() * 10);
            int explainedGaps = signals.isHasUnexplainedGap() ? 0 : (signals.isHasChronologyIssues() ? 1 : 0);

            advancedSignals = new AtsScoreResponse.AdvancedSignals(
                new AtsScoreResponse.TimelineSignals(
                    signals.isHasConcurrentRoles() ? 1 : 0,
                    signals.isHasUnexplainedGap() ? 1 : 0,
                    explainedGaps),
                new AtsScoreResponse.WritingSignals(tenseIssues, terminologyDrift, null),
                new AtsScoreResponse.CredibilitySignals(uncoveredSkills,
                    (int)(signals.getMetricDensity() * 100), weakMetrics, strongMetrics));
        }

        // keywordCoverageBySection — populated from NLP analysis when signals available
        Object keywordCoverageBySection = null;
        if (signals != null && signals.getMustHaveResults() != null && !signals.getMustHaveResults().isEmpty()) {
            List<String> matched = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() != SkillVisibility.MISSING)
                .map(SkillMatchResult::getJdSkill).collect(Collectors.toList());
            String summaryText = resume.getSummaryText() != null ? resume.getSummaryText().toLowerCase() : "";
            String skillsText  = resume.getSkills() != null ? resume.getSkills().stream()
                .map(s -> s.getRawName() != null ? s.getRawName().toLowerCase() : "").collect(Collectors.joining(" ")) : "";
            String expText = resume.getExperience() != null ? resume.getExperience().stream()
                .flatMap(e -> e.getBullets() != null ? e.getBullets().stream() : java.util.stream.Stream.of())
                .collect(Collectors.joining(" ")).toLowerCase() : "";
            Map<String, List<String>> coverage = new LinkedHashMap<>();
            coverage.put("professionalSummary", matched.stream().filter(k -> summaryText.contains(k.toLowerCase())).collect(Collectors.toList()));
            coverage.put("skills",              matched.stream().filter(k -> skillsText.contains(k.toLowerCase())).collect(Collectors.toList()));
            coverage.put("workExperience",      matched.stream().filter(k -> expText.contains(k.toLowerCase())).collect(Collectors.toList()));
            coverage.put("education",           List.of());
            coverage.put("projects",            List.of());
            coverage.put("certifications",      List.of());
            keywordCoverageBySection = coverage;
        }

        return new AtsScoreResponse.EvidenceSummary(
            present, missing, List.of(),
            matchedKw, missingKw,
            yoeMin != null ? yoeMin.intValue() : null,
            yoe != null ? (int) Math.round(yoe) : null,
            meetsYoe, null, null,
            List.of(), List.of(), List.of(),
            null, null, null,
            false, false,
            parseIssues, parseWarnings,
            keywordCoverageBySection, advancedSignals);
    }

    // ── Advanced insights ─────────────────────────────────────────────────────

    private AtsScoreResponse.AdvancedInsights buildAdvancedInsights(
            int overallScore, Resume resume, List<String> bullets,
            ResumeSignals signals, CoherenceEngine.CoherenceResult coherence, boolean hasJd) {

        int delta = resume.getParseConfidence() < 0.5 ? 12 : 8;
        if (coherence != null && coherence.penalty() > 0.1) delta += 4;
        int lower = clamp(overallScore - delta), upper = clamp(overallScore + delta);

        List<AtsScoreResponse.ConfidenceContributor> contributors = new ArrayList<>();
        if (bullets.size() < 5) contributors.add(new AtsScoreResponse.ConfidenceContributor("Low bullet count", "high", "Fewer bullets reduce scoring signal quality."));
        if (resume.getParseConfidence() < 0.6) contributors.add(new AtsScoreResponse.ConfidenceContributor("Parse confidence", "medium", "Complex formatting may have caused extraction errors."));
        if (coherence != null && !coherence.flags().isEmpty()) contributors.add(new AtsScoreResponse.ConfidenceContributor("Coherence flags", "medium", coherence.flags().get(0).detail()));

        // Qualitative tier — honest about the absence of a real benchmark database
        String tier = overallScore >= 85 ? "top" : overallScore >= 70 ? "upper-mid" : overallScore >= 55 ? "lower-mid" : "bottom";

        List<AtsScoreResponse.Counterfactual> counterfactuals = new ArrayList<>();
        if (bullets.stream().filter(b -> ACHIEVEMENT_METRIC.matcher(b).find()).count() < bullets.size() * 0.5)
            counterfactuals.add(new AtsScoreResponse.Counterfactual("Add metrics to 60%+ of bullets", 3, "Quantified bullets are the single highest-impact ATS improvement.", "high"));
        if (signals != null && signals.isHasMissingMustHaves())
            counterfactuals.add(new AtsScoreResponse.Counterfactual("Add missing required skills", 5, "Missing required skills cause automatic ATS rejection.", "critical"));
        if (!hasJd) {
            counterfactuals.add(new AtsScoreResponse.Counterfactual("Add a job description to unlock keyword gap analysis", 4, "Keyword-matched resumes pass ATS at 3× the rate of generic ones. Paste a JD to see exact missing keywords.", "high"));
        }

        // Evidence graph: skills with bullet evidence
        List<AtsScoreResponse.EvidenceGraphEntry> evidenceGraph = new ArrayList<>();
        if (signals != null && signals.getMustHaveResults() != null) {
            for (SkillMatchResult r : signals.getMustHaveResults().stream()
                    .filter(r -> r.getVisibility() != SkillVisibility.MISSING).limit(5).toList()) {
                List<String> evidenceBullets = r.getSourceText() != null ? List.of(r.getSourceText()) : List.of();
                evidenceGraph.add(new AtsScoreResponse.EvidenceGraphEntry(r.getJdSkill(), evidenceBullets, List.of()));
            }
        }

        return new AtsScoreResponse.AdvancedInsights(
            new AtsScoreResponse.ConfidenceInsight(overallScore,
                new AtsScoreResponse.UncertaintyBand(lower, upper, delta), contributors),
            new AtsScoreResponse.BenchmarkInsight(tier, 0,
                "Self-assessed quality tier (no external benchmark data)",
                List.of()),
            counterfactuals,
            evidenceGraph);
    }

    // ── Quality sections (structured per-section data for frontend) ───────────
    // Replaces fragile debug-string parsing — each section has score, issues[], suggestion.

    private List<AtsScoreResponse.QualitySection> buildQualitySections(
            List<String> bullets,
            AtsReport.AtsSection qi, AtsReport.AtsSection av, AtsReport.AtsSection acc,
            AtsReport.AtsSection rep, AtsReport.AtsSection len, AtsReport.AtsSection fw,
            AtsReport.AtsSection tb, AtsReport.AtsSection bl,
            AtsReport.AtsSection sec, AtsReport.AtsSection pro, AtsReport.AtsSection bw,
            AtsReport.AtsSection act, AtsReport.AtsSection con, AtsReport.AtsSection dat,
            AtsReport.AtsSection sp) {

        long quantifiedCount = bullets.stream()
            .filter(b -> ACHIEVEMENT_METRIC.matcher(b).find()).count();
        int totalBulletCount = bullets.size();

        String avgLength = bullets.isEmpty() ? "0 words" :
            String.format("%.0f words avg",
                bullets.stream().mapToInt(b -> b.split("\\s+").length).average().orElse(0));

        List<AtsScoreResponse.QualitySection> out = new ArrayList<>();

        // Impact group
        out.add(toQuality(qi, (int) quantifiedCount, totalBulletCount, null));
        out.add(toQuality(av, null, null, null));
        out.add(toQuality(acc, null, null, null));
        out.add(toQuality(rep, null, null, null));

        // Brevity group
        out.add(toQuality(len, null, null, null));
        out.add(toQuality(fw, null, null, null));
        out.add(toQuality(tb, null, null, null));
        out.add(toQuality(bl, null, null, avgLength));

        // Style group
        out.add(toQuality(sec, null, null, null));
        out.add(toQuality(pro, null, null, null));
        out.add(toQuality(bw, null, null, null));
        out.add(toQuality(act, null, null, null));
        out.add(toQuality(con, null, null, null));
        out.add(toQuality(dat, null, null, null));
        out.add(toQuality(sp, null, null, null));

        return out;
    }

    private AtsScoreResponse.QualitySection toQuality(AtsReport.AtsSection s,
                                                        Integer quantified, Integer total,
                                                        String avgBulletLength) {
        String sev = s.score() >= 80 ? "good" : s.score() >= 55 ? "info" : s.score() >= 40 ? "warning" : "critical";
        String suggestion = s.suggestions().isEmpty() ? null : s.suggestions().get(0);
        AtsScoreResponse.QualitySection base = new AtsScoreResponse.QualitySection(
            s.id(), s.label(), s.score(), sev,
            s.issues(), suggestion,
            quantified, total, avgBulletLength,
            null, null, null);
        return adviceService.advise(base);
    }

    // ── SOTA intelligence builders ────────────────────────────────────────────

    private AtsScoreResponse.BulletIntelligence buildBulletIntelligence(BulletEnricher.EnrichmentResult enrichment) {
        if (enrichment == null) return null;

        List<AtsScoreResponse.EnrichedBulletData> bulletData = enrichment.bullets().stream()
            .map(b -> new AtsScoreResponse.EnrichedBulletData(
                b.text(), b.roleTitle(), b.company(),
                b.metricDetected(), b.actionVerbQuality(),
                b.impactDirection(), b.scopeSignal(),
                (int) b.specificityScore(),
                b.credibilityFlag(), b.duplicateFlag()))
            .collect(Collectors.toList());

        int dupCount   = (int) enrichment.bullets().stream().filter(BulletEnricher.EnrichedBullet::duplicateFlag).count();
        int credCount  = (int) enrichment.bullets().stream().filter(BulletEnricher.EnrichedBullet::credibilityFlag).count();

        return new AtsScoreResponse.BulletIntelligence(enrichment.topBullets(), bulletData, dupCount, credCount);
    }

    private AtsScoreResponse.SkillIntelligence buildSkillIntelligence(
            ResumeSignals signals, JobDescription jd, String rawText) {
        if (signals == null) return null;

        int credScore  = clamp((int) Math.round(signals.getSkillCredibilityScore() * 100));
        List<String> shallow  = signals.getShallowSkills() != null ? signals.getShallowSkills() : List.of();
        List<String> implied  = signals.getImpliedSkillsFound() != null ? signals.getImpliedSkillsFound() : List.of();

        // Keyword density: fraction of required skills appearing 3+ times
        int densityScore = 0;
        if (jd != null && jd.getMustHaveSkills() != null && !jd.getMustHaveSkills().isEmpty()) {
            String lower = rawText.toLowerCase();
            long dense = jd.getMustHaveSkills().stream()
                .filter(s -> s != null && countOccurrences(lower, s.toLowerCase()) >= 3)
                .count();
            densityScore = clamp((int) Math.round((double) dense / jd.getMustHaveSkills().size() * 100));
        } else {
            densityScore = clamp((int) Math.round(signals.getKeywordDensityScore() * 100));
        }

        return new AtsScoreResponse.SkillIntelligence(
            credScore, signals.isHasUnevidencedSkills(), shallow, implied, densityScore);
    }

    private AtsScoreResponse.CoherenceReport buildCoherenceReport(CoherenceEngine.CoherenceResult coherence) {
        if (coherence == null) return null;

        List<AtsScoreResponse.CoherenceFlagData> flags = coherence.flags().stream()
            .map(f -> new AtsScoreResponse.CoherenceFlagData(
                f.type(), f.detail(), f.severity().name()))
            .collect(Collectors.toList());

        int penaltyPct      = clamp((int) Math.round(coherence.penalty() * 100));
        int transferable    = clamp((int) Math.round(coherence.transferableSkillScore() * 100));
        String pivotType    = coherence.pivotType();

        return new AtsScoreResponse.CoherenceReport(flags, penaltyPct, transferable, pivotType);
    }

    private AtsScoreResponse.SeniorityCalibration buildSeniorityCalibration(
            ResumeSignals signals, Resume resume, List<String> bullets) {
        String title = resume.getCurrentTitle();
        if (title == null) title = signals != null ? signals.getCandidateTitle() : null;
        if (title == null) return null;

        int demonstrated;
        if (signals != null && signals.getDemonstratedSeniorityLevel() > 0) {
            demonstrated = signals.getDemonstratedSeniorityLevel();
        } else {
            // Resume-only mode or signal pipeline didn't compute seniority —
            // estimate directly from bullet verb quality, metric density, and scope signals.
            demonstrated = bulletScorer.estimateDemonstratedSeniority(bullets);
        }
        String label = switch (demonstrated) {
            case 0 -> "Unknown";
            case 1 -> "Intern";
            case 2 -> "Junior";
            case 3 -> "Mid";
            case 4 -> "Senior";
            case 5 -> "Lead/Staff";
            default -> "Principal+";
        };

        // Map claimed title to IC level for alignment check
        String tl = title.toLowerCase();
        int claimed = tl.matches(".*\\b(intern|junior|jr)\\b.*")           ? 2
                    : tl.matches(".*\\bmid\\b.*")                           ? 3
                    : tl.matches(".*\\b(senior|sr)\\b.*")                   ? 4
                    : tl.matches(".*\\b(lead|staff|principal)\\b.*")        ? 5
                    : tl.matches(".*\\b(director|vp|head|chief)\\b.*")      ? 6
                    : 0;  // 0 = unknown

        boolean aligned = demonstrated == 0 || claimed == 0 || Math.abs(claimed - demonstrated) <= 1;

        return new AtsScoreResponse.SeniorityCalibration(title, demonstrated, label, aligned);
    }
}
