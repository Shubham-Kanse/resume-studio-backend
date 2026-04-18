package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeScore;
import com.resumestudio.reviewer.model.ResumeScore.ScoreItem;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.enums.*;
import com.resumestudio.reviewer.skills.MindTechOntology;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ResumeScoreCalculator {

    private final MindTechOntology ontology;

    public ResumeScoreCalculator(MindTechOntology ontology) {
        this.ontology = ontology;
    }

    public ResumeScore calculate(ResumeSignals signals) {
        List<ScoreItem> breakdown = new ArrayList<>();

        // ── Skill match (weight 10) ───────────────────────────────────────────
        breakdown.add(scoreSkillMatch(signals));

        // ── YOE fit (weight 12) — data: r=0.576, second strongest predictor ──
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

        // ── Projects / portfolio (weight 4) — data: r=0.331, 5+ projects → 92% hire
        breakdown.add(scoreProjects(signals));

        // ── Composite: weighted average ───────────────────────────────────────
        int totalWeight = breakdown.stream().mapToInt(ScoreItem::getWeight).sum();
        int weightedSum = breakdown.stream().mapToInt(ScoreItem::getWeightedScore).sum();
        // P8: use Math.round to avoid integer truncation at grade boundaries
        int composite = totalWeight > 0 ? Math.min(100, (int) Math.round((double) weightedSum * 10 / totalWeight)) : 0;

        // Cap composite when JD clarity is LOW — a high score against a vague JD is misleading
        if (signals.getJdClarity() != null && signals.getJdClarity() == com.resumestudio.reviewer.model.enums.JdClarity.LOW) {
            composite = Math.min(composite, 65);
        }

        // P5: company belongs in experienceScore, not tailoring
        int skillMatchScore = avg(breakdown, "skill_match", "skills_format");
        int experienceScore = avg(breakdown, "yoe_fit", "title_match", "chronology", "company");
        int presentationScore = avg(breakdown, "summary", "bullets", "format");
        int tailoringScoreVal = avg(breakdown, "tailoring"); // P5: tailoring only

        ResumeScore score = new ResumeScore();
        score.setComposite(composite);
        score.setGrade(grade(composite));
        score.setVerdict(verdict(composite));
        score.setSkillMatchScore(skillMatchScore);
        score.setExperienceScore(experienceScore);
        score.setPresentationScore(presentationScore);
        score.setTailoringScore(tailoringScoreVal);
        // P9: include skills_format in formatScore
        score.setFormatScore(avg(breakdown, "format", "skills_format"));
        score.setBreakdown(breakdown);
        return score;
    }

    // ── Signal scorers ────────────────────────────────────────────────────────

    private ScoreItem scoreSkillMatch(ResumeSignals s) {
        if (s.getMustHaveResults() == null || s.getMustHaveResults().isEmpty()) {
            return item("skill_match", "Skill match", 50, 10, "FAIR", "No JD skills to match against.");
        }

        SkillGroupUtils.GroupedCounts counts = SkillGroupUtils.count(s.getMustHaveResults(), ontology);
        double visibilityPenalty = counts.buried() * 0.05;
        int score = clamp((int) Math.round(counts.found() * 100.0 / counts.total() - visibilityPenalty * 100));

        String obs = counts.found() + " of " + counts.total() + " required skills found" +
            (counts.buried() > 0 ? ", " + counts.buried() + " buried in older roles" : "") + ".";
        return item("skill_match", "Skill match", score, 10, tier(score), obs);
    }

    private ScoreItem scoreYoe(ResumeSignals s) {
        if (s.isChronologyUnreliable()) return item("yoe_fit", "Years of experience", 10, 12, "CRITICAL", "Chronology is unreliable — YOE cannot be verified.");
        YoeFit fit = s.getYoeFit();
        if (fit == null) return item("yoe_fit", "Years of experience", 30, 12, "POOR", "Experience level could not be determined.");
        int score = switch (fit) {
            case IN_RANGE -> 90;
            case OVER_RANGE -> 70;
            case UNDER_RANGE_MINOR -> 55;
            case UNDER_RANGE_SIGNIFICANT -> 20;
            case CANNOT_DETERMINE -> 30;
        };
        String yoe = s.getCalculatedYoe() != null ? String.format("%.1f", s.getCalculatedYoe()).replaceAll("\\.0$", "") + " yrs" : "unknown";
        return item("yoe_fit", "Years of experience", score, 12, tier(score), yoe + " vs " + buildRange(s) + " required.");
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
        // Base: verb quality + metric density
        int base = (int) Math.round(verbRatio * 30 + metricDensity * 70);

        // NLU boost: intent alignment — bullets semantically match JD responsibilities
        double intentBoost = 0;
        if (s.getIntentAlignmentScore() > 0.6) intentBoost = 10;
        else if (s.getIntentAlignmentScore() > 0.5) intentBoost = 5;

        // NLU penalty: shallow skills — required skills mentioned only once
        int shallowPenalty = s.getShallowSkills() != null ? Math.min(15, s.getShallowSkills().size() * 5) : 0;

        int score = clamp((int)(base + intentBoost - shallowPenalty));
        String obs = String.format("%.0f%% impact verbs, %.0f%% quantified results", verbRatio * 100, metricDensity * 100);
        if (!s.getShallowSkills().isEmpty()) obs += "; shallow evidence for: " + String.join(", ", s.getShallowSkills().stream().limit(2).toList());
        return item("bullets", "Bullet quality", score, 7, tier(score), obs);
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
        if (s.isJobHopper()) return item("chronology", "Chronology", 35, 5, "POOR", "Multiple short tenures detected."); // P7: 35 not 45
        if (s.isHasChronologyIssues()) return item("chronology", "Chronology", 60, 5, "GOOD", "Minor chronology issues."); // P7: 60 not 55
        return item("chronology", "Chronology", 90, 5, "EXCELLENT", "Clean, verifiable work history.");
    }

    private ScoreItem scoreTailoring(ResumeSignals s) {
        int base = 0;
        int count = 0;
        if (s.getMustHaveResults() != null && !s.getMustHaveResults().isEmpty()) {
            SkillGroupUtils.GroupedCounts gc = SkillGroupUtils.count(s.getMustHaveResults(), ontology);
            base += (int)(gc.found() * 100 / gc.total());
            count++;
        }
        if (s.isSummaryMentionsSkills()) { base += 80; count++; }
        if (s.getTitleMatch() == TitleMatch.EXACT || s.getTitleMatch() == TitleMatch.ADJACENT) { base += 80; count++; }

        // NLU: domain depth — required skills evidenced in 2+ bullets
        if (s.getDomainDepthScore() > 0) {
            base += (int)(s.getDomainDepthScore() * 80);
            count++;
        }

        // Keyword density: ATS cheatsheet says primary keywords should appear 3-5x
        if (s.getKeywordDensityScore() > 0) {
            base += (int)(s.getKeywordDensityScore() * 80);
            count++;
        }

        int score = count > 0 ? clamp(base / count) : 40;

        String obs;
        if (s.getMustHaveResults() != null && !s.getMustHaveResults().isEmpty()) {
            SkillGroupUtils.GroupedCounts gc = SkillGroupUtils.count(s.getMustHaveResults(), ontology);
            obs = gc.found() + "/" + gc.total() + " required skills present" +
                (gc.missing() > 0 ? ", " + gc.missing() + " missing" : "") +
                (s.isSummaryMentionsSkills() ? ", summary mentions JD skills" : ", summary doesn't mention JD skills");
        } else {
            obs = s.isSummaryMentionsSkills() ? "Summary mentions JD skills" : "No JD skills to match against";
        }
        return item("tailoring", "Tailoring", score, 5, tier(score), obs);
    }

    /**
     * Projects / portfolio score.
     * Data-calibrated: 5+ projects → 92% hire rate, 3+ → 87%, 0 → 68%.
     * Especially important for candidates with low YOE (fresher/bootcamp).
     */
    private ScoreItem scoreProjects(ResumeSignals s) {
        if (!s.isHasProjectsSection()) {
            // No projects section — neutral for senior candidates, mild penalty for juniors
            boolean isJunior = s.getCalculatedYoe() != null && s.getCalculatedYoe() < 3.0;
            int score = isJunior ? 30 : 60;
            return item("projects", "Projects & portfolio", score, 4, tier(score),
                isJunior ? "No projects section — important for early-career candidates." : "No projects section.");
        }
        // Has projects — score based on YOE context
        // For senior candidates, projects are a bonus; for juniors, they're essential
        boolean isJunior = s.getCalculatedYoe() != null && s.getCalculatedYoe() < 3.0;
        int score = isJunior ? 85 : 75;
        return item("projects", "Projects & portfolio", score, 4, tier(score),
            "Projects section present" + (isJunior ? " — strong signal for early-career candidate." : "."));
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
