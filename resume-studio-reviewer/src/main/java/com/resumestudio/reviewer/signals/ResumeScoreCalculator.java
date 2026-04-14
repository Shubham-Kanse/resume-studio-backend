package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeScore;
import com.resumestudio.reviewer.model.ResumeScore.ScoreItem;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.enums.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SOTA weighted scoring engine.
 *
 * Scoring philosophy:
 *   - Skill match is the dominant signal (weight 10) — no amount of polish compensates for missing skills
 *   - YOE fit and title match are hard filters (weight 8)
 *   - Presentation (summary, bullets, format) is a multiplier — it amplifies or dampens the core signals
 *   - Company context and tailoring are positive differentiators (weight 4–5)
 *
 * Score ranges per signal:
 *   EXCELLENT  80–100
 *   GOOD       60–79
 *   FAIR       40–59
 *   POOR       20–39
 *   CRITICAL   0–19
 */
@Component
public class ResumeScoreCalculator {

    public ResumeScore calculate(ResumeSignals signals) {
        List<ScoreItem> breakdown = new ArrayList<>();

        // ── Skill match (weight 10) ───────────────────────────────────────────
        breakdown.add(scoreSkillMatch(signals));

        // ── YOE fit (weight 8) ────────────────────────────────────────────────
        breakdown.add(scoreYoe(signals));

        // ── Title match (weight 8) ────────────────────────────────────────────
        breakdown.add(scoreTitleMatch(signals));

        // ── Summary quality (weight 6) ────────────────────────────────────────
        breakdown.add(scoreSummary(signals));

        // ── Bullet quality (weight 7) ─────────────────────────────────────────
        breakdown.add(scoreBullets(signals));

        // ── Skills format / visibility (weight 6) ────────────────────────────
        breakdown.add(scoreSkillsFormat(signals));

        // ── Company context (weight 4) ────────────────────────────────────────
        breakdown.add(scoreCompany(signals));

        // ── Format & layout (weight 4) ────────────────────────────────────────
        breakdown.add(scoreFormat(signals));

        // ── Chronology / trust (weight 5) ────────────────────────────────────
        breakdown.add(scoreChronology(signals));

        // ── Tailoring (weight 5) ──────────────────────────────────────────────
        breakdown.add(scoreTailoring(signals));

        // ── Composite: weighted average ───────────────────────────────────────
        int totalWeight = breakdown.stream().mapToInt(ScoreItem::getWeight).sum();
        int weightedSum = breakdown.stream().mapToInt(ScoreItem::getWeightedScore).sum();
        // weightedScore = score * weight / 10, so composite = weightedSum * 10 / totalWeight
        int composite = totalWeight > 0 ? Math.min(100, weightedSum * 10 / totalWeight) : 0;

        // ── Category scores ───────────────────────────────────────────────────
        int skillMatchScore = avg(breakdown, "skill_match", "skills_format");
        int experienceScore = avg(breakdown, "yoe_fit", "title_match", "chronology");
        int presentationScore = avg(breakdown, "summary", "bullets", "format");
        int tailoringScoreVal = avg(breakdown, "tailoring", "company");

        ResumeScore score = new ResumeScore();
        score.setComposite(composite);
        score.setGrade(grade(composite));
        score.setVerdict(verdict(composite));
        score.setSkillMatchScore(skillMatchScore);
        score.setExperienceScore(experienceScore);
        score.setPresentationScore(presentationScore);
        score.setTailoringScore(tailoringScoreVal);
        score.setFormatScore(breakdown.stream().filter(i -> "format".equals(i.getSignalId())).mapToInt(ScoreItem::getScore).findFirst().orElse(50));
        score.setBreakdown(breakdown);
        return score;
    }

    // ── Signal scorers ────────────────────────────────────────────────────────

    private ScoreItem scoreSkillMatch(ResumeSignals s) {
        if (s.getMustHaveResults() == null || s.getMustHaveResults().isEmpty()) {
            return item("skill_match", "Skill match", 50, 10, "FAIR", "No JD skills to match against.");
        }
        long total = s.getMustHaveResults().size();
        long missing = s.getMustHaveResults().stream()
            .filter(r -> r.getVisibility() == SkillVisibility.MISSING).count();
        long buried = s.getMustHaveResults().stream()
            .filter(r -> r.getVisibility() == SkillVisibility.BURIED).count();
        long found = total - missing;

        double coverageRatio = (double) found / total;
        double visibilityPenalty = buried * 0.05; // each buried skill costs 5 points
        int raw = (int) Math.round(coverageRatio * 100 - visibilityPenalty * 100);
        int score = clamp(raw);

        String tier = tier(score);
        String obs = found + " of " + total + " required skills found" +
            (buried > 0 ? ", " + buried + " buried in older roles" : "") + ".";
        return item("skill_match", "Skill match", score, 10, tier, obs);
    }

    private ScoreItem scoreYoe(ResumeSignals s) {
        if (s.isChronologyUnreliable()) return item("yoe_fit", "Years of experience", 10, 8, "CRITICAL", "Chronology is unreliable — YOE cannot be verified.");
        YoeFit fit = s.getYoeFit();
        if (fit == null) return item("yoe_fit", "Years of experience", 30, 8, "POOR", "Experience level could not be determined.");
        int score = switch (fit) {
            case IN_RANGE -> 90;
            case OVER_RANGE -> 70;
            case UNDER_RANGE_MINOR -> 55;
            case UNDER_RANGE_SIGNIFICANT -> 20;
            case CANNOT_DETERMINE -> 30;
        };
        String yoe = s.getCalculatedYoe() != null ? String.format("%.1f", s.getCalculatedYoe()).replaceAll("\\.0$", "") + " yrs" : "unknown";
        return item("yoe_fit", "Years of experience", score, 8, tier(score), yoe + " vs " + buildRange(s) + " required.");
    }

    private ScoreItem scoreTitleMatch(ResumeSignals s) {
        TitleMatch match = s.getTitleMatch();
        if (match == null) return item("title_match", "Title match", 20, 8, "POOR", "No title detected.");
        int score = switch (match) {
            case EXACT -> 100;
            case ADJACENT -> 80;
            case RELATED -> 55;
            case MISS -> 15;
            default -> 30;
        };
        String obs = s.getCandidateTitle() != null && s.getJdTitle() != null
            ? "\"" + s.getCandidateTitle() + "\" vs \"" + s.getJdTitle() + "\""
            : match.name().toLowerCase().replace("_", " ");
        return item("title_match", "Title match", score, 8, tier(score), obs);
    }

    private ScoreItem scoreSummary(ResumeSignals s) {
        if (!s.isSummaryPresent()) return item("summary", "Summary", 0, 6, "CRITICAL", "No summary section found.");
        if (s.isSummaryIsGeneric()) return item("summary", "Summary", 25, 6, "POOR", "Summary uses generic language with no technical specifics.");
        boolean mentionsSkills = s.isSummaryMentionsSkills();
        boolean mentionsYoe = s.isSummaryMentionsYoe();
        boolean mentionsTitle = s.isSummaryMentionsTitle();
        int score = 50 + (mentionsSkills ? 20 : 0) + (mentionsYoe ? 15 : 0) + (mentionsTitle ? 15 : 0);
        String obs = "Mentions: " + (mentionsTitle ? "title " : "") + (mentionsYoe ? "YOE " : "") + (mentionsSkills ? "skills" : "");
        return item("summary", "Summary", clamp(score), 6, tier(score), obs.trim());
    }

    private ScoreItem scoreBullets(ResumeSignals s) {
        double verbRatio = s.getImpactVerbRatio();
        double metricDensity = s.getMetricDensity();
        // verb ratio 0–1 → 0–50 pts, metric density 0–1 → 0–50 pts
        int score = (int) Math.round(verbRatio * 50 + metricDensity * 50);
        String obs = String.format("%.0f%% impact verbs, %.0f%% quantified results.", verbRatio * 100, metricDensity * 100);
        return item("bullets", "Bullet quality", clamp(score), 7, tier(score), obs);
    }

    private ScoreItem scoreSkillsFormat(ResumeSignals s) {
        SkillsFormat fmt = s.getSkillsFormat();
        if (fmt == null) return item("skills_format", "Skills format", 40, 6, "FAIR", "Skills format unknown.");
        int score = switch (fmt) {
            case OPTIMAL -> 100;
            case CATEGORISED_UNORDERED -> 75;
            case FLAT_ORDERED -> 70;
            case FLAT_UNORDERED -> 50;
            case BULLET_LIST -> 45;
            case PROSE -> 30;
            case MIXED_SOFT_HARD -> 35;
            case SELF_RATED -> 25;
            case GENERIC_ONLY -> 10;
            case NO_SECTION -> 0;
            case OVER_VERSIONED -> 40;
        };
        return item("skills_format", "Skills format", score, 6, tier(score), fmt.name().toLowerCase().replace("_", " "));
    }

    private ScoreItem scoreCompany(ResumeSignals s) {
        CompanyTier tier = s.getCurrentCompanyTier();
        if (tier == null) return item("company", "Company context", 40, 4, "FAIR", "Company not identified.");
        int score = switch (tier) {
            case FAANG -> 100;
            case TIER_1 -> 85;
            case SCALE_UP -> 70;
            case DESCRIBED -> 60;
            case STARTUP -> 50;
            case UNKNOWN -> 35;
        };
        String company = s.getCurrentCompanyName() != null ? s.getCurrentCompanyName() : tier.name();
        return item("company", "Company context", score, 4, tier(score), company + " — " + tier.name().toLowerCase().replace("_", " "));
    }

    private ScoreItem scoreFormat(ResumeSignals s) {
        int score = 100;
        List<String> issues = new ArrayList<>();
        if (s.isFormatWallOfText()) { score -= 30; issues.add("wall of text"); }
        if (s.isFormatTooManyPages()) { score -= 20; issues.add("too many pages"); }
        if (s.isFormatFontTooSmall()) { score -= 20; issues.add("font too small"); }
        if (s.isFormatHasPhoto()) { score -= 10; issues.add("photo present"); }
        if (s.isFormatIsMultiColumn()) { score -= 10; issues.add("multi-column"); }
        if (s.isFormatMixedFonts()) { score -= 10; issues.add("mixed fonts"); }
        String obs = issues.isEmpty() ? "Clean, ATS-friendly layout." : String.join(", ", issues) + ".";
        return item("format", "Format & layout", clamp(score), 4, tier(score), obs);
    }

    private ScoreItem scoreChronology(ResumeSignals s) {
        if (s.isChronologyUnreliable()) return item("chronology", "Chronology", 0, 5, "CRITICAL", "Chronology cannot be trusted.");
        if (s.isHasUnexplainedGap()) return item("chronology", "Chronology", 40, 5, "FAIR", "Unexplained employment gap detected.");
        if (s.isJobHopper()) return item("chronology", "Chronology", 45, 5, "FAIR", "Multiple short tenures detected.");
        if (s.isHasChronologyIssues()) return item("chronology", "Chronology", 55, 5, "FAIR", "Minor chronology issues.");
        return item("chronology", "Chronology", 90, 5, "EXCELLENT", "Clean, verifiable work history.");
    }

    private ScoreItem scoreTailoring(ResumeSignals s) {
        // Tailoring = how well the resume is customised for this specific JD
        // Proxy: skill coverage + summary mentions skills + title match
        int base = 0;
        int count = 0;
        if (s.getMustHaveResults() != null && !s.getMustHaveResults().isEmpty()) {
            long total = s.getMustHaveResults().size();
            long found = s.getMustHaveResults().stream().filter(r -> r.getVisibility() != SkillVisibility.MISSING).count();
            base += (int)(found * 100 / total);
            count++;
        }
        if (s.isSummaryMentionsSkills()) { base += 80; count++; }
        if (s.getTitleMatch() == TitleMatch.EXACT || s.getTitleMatch() == TitleMatch.ADJACENT) { base += 80; count++; }
        int score = count > 0 ? clamp(base / count) : 40;
        return item("tailoring", "Tailoring", score, 5, tier(score), "How well this resume is customised for the role.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ScoreItem item(String id, String label, int score, int weight, String tier, String obs) {
        return new ScoreItem(id, label, score, weight, tier, obs);
    }

    private int clamp(int v) { return Math.max(0, Math.min(100, v)); }

    private String tier(int score) {
        if (score >= 80) return "EXCELLENT";
        if (score >= 60) return "GOOD";
        if (score >= 40) return "FAIR";
        if (score >= 20) return "POOR";
        return "CRITICAL";
    }

    private String grade(int score) {
        if (score >= 85) return "A";
        if (score >= 70) return "B";
        if (score >= 55) return "C";
        if (score >= 40) return "D";
        return "F";
    }

    private String verdict(int score) {
        if (score >= 85) return "Strong";
        if (score >= 70) return "Good";
        if (score >= 55) return "Fair";
        if (score >= 40) return "Weak";
        return "Critical";
    }

    private String buildRange(ResumeSignals s) {
        if (s.getJdYoeMin() == null) return "unspecified";
        if (s.getJdYoeMax() == null) return s.getJdYoeMin().intValue() + "+";
        return s.getJdYoeMin().intValue() + "–" + s.getJdYoeMax().intValue();
    }

    private int avg(List<ScoreItem> items, String... ids) {
        java.util.Set<String> idSet = java.util.Set.of(ids);
        var matching = items.stream().filter(i -> idSet.contains(i.getSignalId())).toList();
        if (matching.isEmpty()) return 50;
        return matching.stream().mapToInt(ScoreItem::getScore).sum() / matching.size();
    }
}
