package com.resumestudio.reviewer.ats;

import com.resumestudio.reviewer.model.Resume;
import com.resumestudio.reviewer.model.WorkExperience;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.resumestudio.reviewer.ats.BulletQualityScorer.*;

/**
 * Orchestrates all ATS scorers and assembles the final AtsReport.
 *
 * Separation of concerns:
 *   BulletQualityScorer → Impact group
 *   BrevityScorer       → Brevity group
 *   StyleScorer         → Style group
 *   AtsScoreService     → aggregation + summary narrative only
 */
@Service
public class AtsScoreService {

    private final BulletQualityScorer bulletScorer;
    private final BrevityScorer brevityScorer;
    private final StyleScorer styleScorer;

    public AtsScoreService(BulletQualityScorer bulletScorer,
                           BrevityScorer brevityScorer,
                           StyleScorer styleScorer) {
        this.bulletScorer = bulletScorer;
        this.brevityScorer = brevityScorer;
        this.styleScorer = styleScorer;
    }

    public AtsReport score(Resume resume, String rawText) {
        List<String> bullets = BulletQualityScorer.extractBullets(
            resume.getExperience() != null ? resume.getExperience() : List.of());

        // ── Impact ────────────────────────────────────────────────────────────
        AtsReport.AtsSection quantifyingImpact = bulletScorer.scoreQuantifyingImpact(bullets);
        AtsReport.AtsSection actionVerbUse     = bulletScorer.scoreActionVerbUse(bullets);
        AtsReport.AtsSection accomplishments   = bulletScorer.scoreAccomplishments(bullets);
        AtsReport.AtsSection repetition        = bulletScorer.scoreRepetition(bullets);

        // ── Brevity ───────────────────────────────────────────────────────────
        AtsReport.AtsSection length            = brevityScorer.scoreLength(rawText);
        AtsReport.AtsSection fillerWords       = brevityScorer.scoreFillerWords(bullets);
        AtsReport.AtsSection totalBullets      = brevityScorer.scoreTotalBulletPoints(bullets);
        AtsReport.AtsSection bulletLength      = brevityScorer.scoreBulletPointsLength(bullets);

        // ── Style ─────────────────────────────────────────────────────────────
        AtsReport.AtsSection sections          = styleScorer.scoreSections(resume);
        AtsReport.AtsSection pronouns          = styleScorer.scorePersonalPronouns(rawText);
        AtsReport.AtsSection activeVoice       = styleScorer.scoreActiveVoice(bullets);
        AtsReport.AtsSection consistency       = styleScorer.scoreConsistency(bullets, rawText);
        AtsReport.AtsSection dateOrder         = styleScorer.scoreDateOrder(
            resume.getExperience() != null ? resume.getExperience() : List.of());
        AtsReport.AtsSection spellCheck        = styleScorer.scoreSpellCheck(rawText);

        // ── Group averages ────────────────────────────────────────────────────
        int impactScore  = avg(quantifyingImpact.score(), actionVerbUse.score(),
                               accomplishments.score(), repetition.score());
        int brevityScore = avg(length.score(), fillerWords.score(),
                               totalBullets.score(), bulletLength.score());
        int styleScore   = avg(sections.score(), pronouns.score(), activeVoice.score(),
                               consistency.score(), dateOrder.score(), spellCheck.score());
        int avgBullet    = avg(quantifyingImpact.score(), actionVerbUse.score(),
                               accomplishments.score(), bulletLength.score());
        int overall      = avg(impactScore, brevityScore, styleScore);

        List<AtsReport.AtsSection> allSections = new ArrayList<>(List.of(
            quantifyingImpact, actionVerbUse, accomplishments, repetition,
            length, fillerWords, totalBullets, bulletLength,
            sections, pronouns, activeVoice, consistency, dateOrder, spellCheck
        ));

        AtsReport report = new AtsReport();
        report.overallScore      = overall;
        report.impactScore       = impactScore;
        report.brevityScore      = brevityScore;
        report.styleScore        = styleScore;
        report.averageBulletScore = avgBullet;
        report.sections          = allSections;
        report.summary           = buildSummary(overall, impactScore, brevityScore, styleScore);
        return report;
    }

    private static int avg(int... scores) {
        if (scores.length == 0) return 0;
        int sum = 0;
        for (int s : scores) sum += s;
        return clamp(sum / scores.length);
    }

    private static String buildSummary(int overall, int impact, int brevity, int style) {
        if (overall >= 85) return "Excellent. Your resume is well-structured, impact-driven, and ATS-ready with only minor refinements left.";
        if (overall >= 70) return "Good foundation. Focus on quantifying more bullets and tightening style consistency to push into the top tier.";
        if (overall >= 55) return "Workable but needs improvement. Strengthen your impact language, reduce filler, and fix style inconsistencies.";
        if (overall >= 40) return "Below average. Your resume needs stronger bullets, better structure, and clearer evidence of impact.";
        return "Needs significant work. Rebuild your bullets around accomplishments, fix missing sections, and clean up formatting.";
    }
}
