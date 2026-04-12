package com.resumestudio.reviewer.nlp;

import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.skills.SkillEmbeddingIndex;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Layer 2b — Bullet enrichment per AI-integration.md.
 *
 * Per bullet computes:
 *   - metricDetected
 *   - actionVerbQuality (STRONG | MEDIUM | WEAK | MISSING)
 *   - impactDirection (IMPROVEMENT | PREVENTION | SCALE | AMBIGUOUS)
 *   - scopeSignal (INDIVIDUAL | TEAM | ORG)
 *   - specificityScore 0–10
 *   - credibilityFlag
 *   - duplicateFlag (cosine similarity > 0.90 within same role)
 *
 * Also selects top 5 bullets across all roles by composite score.
 */
@Component
public class BulletEnricher {

    private static final Pattern METRIC = Pattern.compile(
        "\\d+\\s*[%$xX]|\\d{2,}|\\$\\d+|millions?|billions?|thousands?|daily|monthly|weekly",
        Pattern.CASE_INSENSITIVE);

    private static final Set<String> STRONG_VERBS = Set.of(
        "architected","engineered","led","reduced","eliminated","launched","delivered",
        "increased","improved","automated","migrated","scaled","secured","drove",
        "accelerated","streamlined","introduced","owned","shipped","mentored","designed");

    private static final Set<String> MEDIUM_VERBS = Set.of(
        "developed","built","implemented","created","deployed","integrated","established",
        "managed","coordinated","resolved","diagnosed","refactored");

    private static final Set<String> WEAK_VERBS = Set.of(
        "responsible","helped","assisted","supported","worked","involved",
        "participated","contributed","utilized","leveraged","ensured","maintained");

    private static final Pattern IMPROVEMENT = Pattern.compile(
        "\\b(reduced|improved|increased|accelerated|optimized|optimised|boosted|cut|lowered)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PREVENTION = Pattern.compile(
        "\\b(eliminated|avoided|prevented|mitigated|removed)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCALE = Pattern.compile(
        "\\b(handles|processes|supports|serving|scaling|scaled)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEAM_SCOPE = Pattern.compile(
        "\\b(led team|collaborated|worked with|partnered|cross-functional|team of)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ORG_SCOPE = Pattern.compile(
        "\\b(org-wide|company-wide|all teams|across the org|organisation-wide|organization-wide)\\b",
        Pattern.CASE_INSENSITIVE);

    private final SkillEmbeddingIndex embeddings;

    public BulletEnricher(SkillEmbeddingIndex embeddings) {
        this.embeddings = embeddings;
    }

    public EnrichmentResult enrich(List<WorkExperience> experience, List<String> jdMustHaves) {
        List<EnrichedBullet> all = new ArrayList<>();

        for (WorkExperience role : experience) {
            if (role.getBullets() == null) continue;
            List<float[]> roleEmbeddings = new ArrayList<>();

            for (String bullet : role.getBullets()) {
                if (bullet == null || bullet.isBlank()) continue;

                boolean metric = METRIC.matcher(bullet).find();
                String verbQuality = verbQuality(bullet);
                String impactDir = impactDirection(bullet);
                String scope = scopeSignal(bullet);
                float specificity = specificityScore(bullet, metric, verbQuality);
                boolean credFlag = credibilityFlag(bullet, role);

                // Duplicate detection via embedding cosine similarity
                float[] emb = embeddings.embed(bullet);
                boolean dupFlag = false;
                if (emb != null) {
                    for (float[] prev : roleEmbeddings) {
                        if (cosineSim(emb, prev) > 0.90f) { dupFlag = true; break; }
                    }
                    roleEmbeddings.add(emb);
                }

                all.add(new EnrichedBullet(bullet, role.getTitle(), role.getCompany(),
                    metric, verbQuality, impactDir, scope, specificity, credFlag, dupFlag, emb));
            }
        }

        // Score and select top 5
        List<String> topBullets = all.stream()
            .filter(b -> !b.duplicateFlag() && !b.credibilityFlag())
            .sorted(Comparator.comparingDouble((EnrichedBullet b) -> compositeScore(b)).reversed())
            .limit(5)
            .map(EnrichedBullet::text)
            .toList();

        return new EnrichmentResult(all, topBullets);
    }

    // ── Scoring ───────────────────────────────────────────────────────────────

    private double compositeScore(EnrichedBullet b) {
        double metricScore = b.metricDetected() ? 1.0 : 0.0;
        double verbScore = switch (b.actionVerbQuality()) {
            case "STRONG" -> 1.0; case "MEDIUM" -> 0.6; case "WEAK" -> 0.2; default -> 0.0;
        };
        return metricScore * 0.3 + verbScore * 0.2 + b.specificityScore() / 10.0 * 0.5;
    }

    private String verbQuality(String bullet) {
        String first = firstWord(bullet);
        if (STRONG_VERBS.contains(first)) return "STRONG";
        if (MEDIUM_VERBS.contains(first)) return "MEDIUM";
        if (WEAK_VERBS.contains(first)) return "WEAK";
        return "MISSING";
    }

    private String impactDirection(String bullet) {
        if (IMPROVEMENT.matcher(bullet).find()) return "IMPROVEMENT";
        if (PREVENTION.matcher(bullet).find()) return "PREVENTION";
        if (SCALE.matcher(bullet).find()) return "SCALE";
        return "AMBIGUOUS";
    }

    private String scopeSignal(String bullet) {
        if (ORG_SCOPE.matcher(bullet).find()) return "ORG";
        if (TEAM_SCOPE.matcher(bullet).find()) return "TEAM";
        return "INDIVIDUAL";
    }

    private float specificityScore(String bullet, boolean metric, String verbQuality) {
        float score = 0f;
        if (metric) score += 4f;
        if ("STRONG".equals(verbQuality)) score += 2f;
        else if ("MEDIUM".equals(verbQuality)) score += 1f;
        if (bullet.length() > 80) score += 1f;
        if (bullet.length() > 120) score += 1f;
        // Numbers in general
        if (bullet.matches(".*\\d+.*")) score += 1f;
        return Math.min(10f, score);
    }

    private boolean credibilityFlag(String bullet, WorkExperience role) {
        // "led team of N" where N > 20 and role duration < 2 years
        var m = Pattern.compile("led\\s+(?:a\\s+)?team\\s+of\\s+(\\d+)", Pattern.CASE_INSENSITIVE)
            .matcher(bullet);
        if (m.find()) {
            int teamSize = Integer.parseInt(m.group(1));
            if (teamSize > 20 && role.getDurationYears() < 2.0) return true;
        }
        return false;
    }

    private float cosineSim(float[] a, float[] b) {
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i]; }
        float denom = (float)(Math.sqrt(na) * Math.sqrt(nb));
        return denom == 0 ? 0 : dot / denom;
    }

    private String firstWord(String s) {
        if (s == null || s.isBlank()) return "";
        return s.trim().split("\\s+")[0].toLowerCase().replaceAll("[^a-z]", "");
    }

    // ── Output types ──────────────────────────────────────────────────────────

    public record EnrichedBullet(
        String text, String roleTitle, String company,
        boolean metricDetected, String actionVerbQuality,
        String impactDirection, String scopeSignal,
        float specificityScore, boolean credibilityFlag,
        boolean duplicateFlag, float[] embedding
    ) {}

    public record EnrichmentResult(List<EnrichedBullet> bullets, List<String> topBullets) {}
}
