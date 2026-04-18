package com.resumestudio.reviewer.ats;

import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.nlp.VerbQualityService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Impact group scorer — covers:
 *   quantifying-impact, action-verb-use, accomplishments, repetition
 *
 * Uses VerbQualityService (verb_quality_ontology.json) for verb scoring
 * instead of hardcoded lists.
 */
@Component
public class BulletQualityScorer {

    // Smart achievement metric: distinguishes genuine business/tech outcomes from incidental numbers.
    // Matches currency amounts, percentages, multipliers, business-unit scale, and metric acronyms.
    // Does NOT match: plain years (2019), version numbers (v2.0), or ordinal counts without context.
    static final Pattern ACHIEVEMENT_METRIC = Pattern.compile(
        // Currency: $2M, £500k, €1B
        "(?:\\$|£|€|USD|EUR|GBP)\\s*\\d[\\d,.]*(?:\\s*[kKmMbB](?:illion)?)?|" +
        // Percentage: 40%, 99.9 percent
        "\\d[\\d,.]*\\s*(?:%|percent(?:age)?)|" +
        // Multipliers: 2x, 5x faster
        "\\d+[xX](?=\\s|$|,)|" +
        // Improvement framing: "by 40", "saved 120", "from 3 to 1"
        "\\b(?:by|saved?|saving|cut|grew?|grew|from)\\s+\\d[\\d,.]*|" +
        // Formatted large numbers (commas = intentional scale signal): 1,000 or 10,000
        "\\d{1,3}(?:,\\d{3})+|" +
        // Numbers + business-significant units (team size, user counts, infra counts)
        "\\d[\\d,.]*[kKmMbB]?\\s+(?:users?|customers?|clients?|accounts?|" +
        "engineers?|developers?|employees?|team\\s*members?|" +
        "requests?(?:\\s+per\\s+\\w+)?|transactions?|deployments?|" +
        "releases?|services?|microservices?|APIs?|" +
        "countries?|regions?|markets?|cities?|locations?|" +
        "minutes?|seconds?|hours?|days?|weeks?|months?|ms(?!\\w)|milliseconds?)|" +
        // Business/tech metric acronyms
        "\\b(?:kpi|okr|mrr|arr|roi|nps|csat|dau|mau|p99|p95|p50|aov|ltv|cac|mttr|sla|slo|ebitda)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern OUTCOME_PATTERN =
        Pattern.compile("\\b(increas|reduc|improv|generat|cut|boost|grew|saved|accelerat|shorten|stabiliz|expand|rais|deliver)\\w*\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RESULT_CONNECTOR =
        Pattern.compile("\\b(by|through|resulting in|resulted in|leading to|led to|which|so that)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PASSIVE_PATTERN =
        Pattern.compile("\\b(was|were|is|are|been|being|be)\\s+\\w+(?:ed|en)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> RESPONSIBILITY_PHRASES = List.of(
        "responsible for", "duties included", "tasked with", "participated in",
        "involved in", "helped with", "worked on", "was part of", "part of",
        "assisted with", "assisted", "supported"
    );

    private final VerbQualityService verbQuality;

    public BulletQualityScorer(VerbQualityService verbQuality) {
        this.verbQuality = verbQuality;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public AtsReport.AtsSection scoreQuantifyingImpact(List<String> bullets) {
        if (bullets.isEmpty()) return section("quantifying-impact", "Quantifying Impact", 50,
            List.of("No experience bullets found."), List.of("Add quantified achievements to your experience section."));

        int quantified = 0;
        List<String> issues = new ArrayList<>();
        for (String b : bullets) {
            boolean hasMetric = ACHIEVEMENT_METRIC.matcher(b).find();
            boolean hasOutcome = OUTCOME_PATTERN.matcher(b).find();
            boolean hasConnector = RESULT_CONNECTOR.matcher(b).find();
            if (hasMetric || (hasOutcome && hasConnector)) {
                quantified++;
            } else {
                if (issues.size() < 3) issues.add("Missing metric: " + truncate(b));
            }
        }

        double ratio = (double) quantified / bullets.size();
        int score = clamp((int) (ratio * 100));
        List<String> suggestions = ratio < 0.5
            ? List.of("Add numbers, percentages, or scale to at least half your bullets.",
                      "Use patterns like 'reduced X by Y%' or 'served N users'.")
            : List.of();
        return section("quantifying-impact", "Quantifying Impact", score, issues, suggestions);
    }

    public AtsReport.AtsSection scoreActionVerbUse(List<String> bullets) {
        if (bullets.isEmpty()) return section("action-verb-use", "Action Verb Use", 50,
            List.of("No bullets found."), List.of());

        double weightSum = 0.0;
        List<String> issues = new ArrayList<>();

        for (String b : bullets) {
            String verb = firstWord(b);
            boolean hasMetric = ACHIEVEMENT_METRIC.matcher(b).find();
            boolean isPassive = PASSIVE_PATTERN.matcher(b).find();

            VerbQualityService.VerbEntry entry = verbQuality.lookup(verb);
            if (entry != null) {
                double ew = verbQuality.effectiveWeight(verb, hasMetric, isPassive);
                weightSum += Math.min(1.0, ew);

                String q = entry.quality;
                if (("WEAK".equals(q) || "TOXIC".equals(q)) && issues.size() < 3) {
                    String sug = entry.suggestion != null ? " → " + entry.suggestion : "";
                    issues.add("Weak opener '" + verb + "': " + truncate(b) + sug);
                } else if (("ELITE".equals(q) || "STRONG".equals(q))
                            && !hasMetric && entry.missingMetricMessage != null && issues.size() < 3) {
                    issues.add(entry.missingMetricMessage + ": " + truncate(b));
                }
            } else {
                weightSum += 0.4; // unknown verb → MODERATE baseline
            }
        }

        int score = clamp((int) Math.round((weightSum / bullets.size()) * 100));
        List<String> suggestions = score < 70
            ? List.of("Open bullets with strong action verbs like 'Built', 'Reduced', 'Led'.",
                      "Replace weak openers like 'Responsible for' or 'Helped'.")
            : List.of();
        return section("action-verb-use", "Action Verb Use", score, issues, suggestions);
    }

    public AtsReport.AtsSection scoreAccomplishments(List<String> bullets) {
        if (bullets.isEmpty()) return section("accomplishments", "Accomplishments", 50,
            List.of("No bullets found."), List.of());

        int accomplishment = 0;
        List<String> issues = new ArrayList<>();
        for (String b : bullets) {
            String lower = b.toLowerCase();
            boolean isResponsibility = RESPONSIBILITY_PHRASES.stream()
                .anyMatch(p -> lower.contains(p));
            boolean hasMetric = ACHIEVEMENT_METRIC.matcher(b).find();
            boolean hasOutcome = OUTCOME_PATTERN.matcher(b).find() && RESULT_CONNECTOR.matcher(b).find();

            if (isResponsibility && (hasMetric || hasOutcome)) {
                accomplishment++; // metric evidence rescues responsibility-framed bullet
            } else if (isResponsibility) {
                if (issues.size() < 3) issues.add("Responsibility language: " + truncate(b));
            } else if (hasMetric || hasOutcome) {
                accomplishment++;
            }
        }

        double ratio = (double) accomplishment / bullets.size();
        int score = clamp((int) (ratio * 100) - issues.size() * 10);
        List<String> suggestions = ratio < 0.4
            ? List.of("Rewrite responsibility statements as accomplishments with visible outcomes.",
                      "Use STAR-T format: Situation, Task, Action, Result, Tools.")
            : List.of();
        return section("accomplishments", "Accomplishments", score, issues, suggestions);
    }

    public AtsReport.AtsSection scoreRepetition(List<String> bullets) {
        Map<String, Integer> verbCounts = new LinkedHashMap<>();
        for (String b : bullets) {
            String v = firstWord(b);
            if (!v.isEmpty()) verbCounts.merge(v, 1, Integer::sum);
        }

        List<String> issues = new ArrayList<>();
        int repeatedCount = 0;
        int totalVerbs = verbCounts.values().stream().mapToInt(i -> i).sum();

        for (Map.Entry<String, Integer> e : verbCounts.entrySet()) {
            if (e.getValue() > 2) {
                repeatedCount += e.getValue();
                String q = verbQuality.quality(e.getKey());
                String label = q != null ? " (" + q.toLowerCase() + " verb)" : "";
                issues.add("'" + e.getKey() + "' used " + e.getValue() + " times" + label);
            }
        }

        double ratio = totalVerbs > 0 ? (double) repeatedCount / totalVerbs : 0;
        int score = repeatedCount == 0 ? 100 : clamp((int) ((1 - ratio) * 100));
        List<String> suggestions = repeatedCount > 0
            ? List.of("Vary your opening verbs — use synonyms from different action verb categories.",
                      "Replace repeated weak verbs with stronger alternatives from the impact verb list.")
            : List.of();
        return section("repetition", "Repetition", score, issues, suggestions);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static List<String> extractBullets(List<WorkExperience> experience) {
        List<String> bullets = new ArrayList<>();
        for (WorkExperience exp : experience) {
            if (exp.getBullets() != null) bullets.addAll(exp.getBullets());
        }
        return bullets;
    }

    private String firstWord(String bullet) {
        String clean = bullet.replaceAll("^[-*•]\\s*", "").trim();
        String[] parts = clean.split("\\s+");
        return parts.length > 0 ? parts[0].toLowerCase().replaceAll("[^a-z]", "") : "";
    }

    private String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "…" : s;
    }

    static int clamp(int v) { return Math.max(0, Math.min(100, v)); }

    static String status(int score) {
        if (score >= 80) return "good";
        if (score >= 55) return "fair";
        return "poor";
    }

    static AtsReport.AtsSection section(String id, String label, int score,
                                         List<String> issues, List<String> suggestions) {
        return new AtsReport.AtsSection(id, label, score, status(score), issues, suggestions);
    }

    /**
     * Estimates IC seniority level 1–6 from bullet verb quality, metric density, and scope signals.
     * Used in resume-only mode where the signal pipeline doesn't run.
     */
    public int estimateDemonstratedSeniority(List<String> bullets) {
        if (bullets.isEmpty()) return 0;

        int eliteStrong = 0, medium = 0, weak = 0, metricCount = 0;
        int orgScope = 0, teamScope = 0;
        java.util.regex.Pattern orgPattern = java.util.regex.Pattern.compile(
            "\\b(org-wide|company-wide|all teams|across the org|organisation-wide|organization-wide|enterprise-wide)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern teamPattern = java.util.regex.Pattern.compile(
            "\\b(led team|team of \\d|collaborated|cross-functional|partnered)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

        for (String b : bullets) {
            String v = firstWord(b);
            String q = verbQuality.quality(v);
            if ("ELITE".equals(q) || "STRONG".equals(q)) eliteStrong++;
            else if ("GOOD".equals(q) || "MODERATE".equals(q)) medium++;
            else if ("WEAK".equals(q) || "TOXIC".equals(q)) weak++;
            if (ACHIEVEMENT_METRIC.matcher(b).find()) metricCount++;
            if (orgPattern.matcher(b).find()) orgScope++;
            else if (teamPattern.matcher(b).find()) teamScope++;
        }

        int total = bullets.size();
        double eliteRatio = (double) eliteStrong / total;
        double metricRatio = (double) metricCount / total;

        if (orgScope >= 2 && eliteRatio >= 0.4 && metricRatio >= 0.3) return 5; // Lead/Staff
        if ((teamScope >= 2 || eliteRatio >= 0.35) && metricRatio >= 0.25) return 4; // Senior
        if (eliteRatio >= 0.15 && metricRatio >= 0.15) return 3; // Mid
        if (medium > weak) return 2; // Junior
        return 1; // Intern/basic
    }
}
