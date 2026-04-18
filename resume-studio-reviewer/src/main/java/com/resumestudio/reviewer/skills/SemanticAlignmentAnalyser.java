package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.JobDescription;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.WorkExperience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * NLU layer: sentence-level semantic alignment between JD intent and resume evidence.
 *
 * Two analyses:
 *
 * 1. INTENT ALIGNMENT — Uses MiniLM embeddings to find how well the candidate's
 *    bullets semantically match the JD's responsibility sentences (not just skills).
 *    "Own the reliability of our payment infrastructure" ↔
 *    "Led incident response for fintech platform serving 2M users" → high alignment
 *    even if "reliability" and "incident response" don't share keywords.
 *
 * 2. DOMAIN DEPTH — Counts how many distinct bullets mention each required skill.
 *    A candidate with 8 Python bullets across ML, APIs, and data pipelines has
 *    deeper Python expertise than one with 1 Python mention.
 *    Produces a per-skill depth score and an overall depth signal.
 *
 * Both signals feed into the AI prompt for richer narrative generation.
 */
@Component
public class SemanticAlignmentAnalyser {

    private static final Logger log = LoggerFactory.getLogger(SemanticAlignmentAnalyser.class);

    // Minimum cosine similarity to count as a semantic match
    private static final float INTENT_MATCH_THRESHOLD = 0.55f;

    private final SkillEmbeddingIndex embeddings;

    public SemanticAlignmentAnalyser(SkillEmbeddingIndex embeddings) {
        this.embeddings = embeddings;
    }

    /**
     * Runs both analyses and sets signals.
     *
     * Sets:
     *   signals.intentAlignmentScore  — 0–1: how well bullets match JD responsibilities
     *   signals.topAlignedBullet      — the resume bullet most semantically aligned with JD
     *   signals.domainDepthScore      — 0–1: breadth of evidence per required skill
     *   signals.shallowSkills         — required skills with only 1 bullet mention
     */
    public void analyse(JobDescription jd, List<WorkExperience> experience, ResumeSignals signals) {
        List<String> bullets = experience == null ? List.of() :
            experience.stream()
                .filter(e -> e.getBullets() != null)
                .flatMap(e -> e.getBullets().stream())
                .filter(b -> b != null && b.length() > 20)
                .collect(Collectors.toList());

        if (bullets.isEmpty()) return;

        // ── 1. Intent alignment ───────────────────────────────────────────
        analyseIntentAlignment(jd, bullets, signals);

        // ── 2. Domain depth ───────────────────────────────────────────────
        analyseDomainDepth(jd, bullets, signals);
    }

    private void analyseIntentAlignment(JobDescription jd, List<String> bullets, ResumeSignals signals) {
        // Extract JD responsibility sentences — the "what you'll do" part
        List<String> jdSentences = extractResponsibilitySentences(jd);
        if (jdSentences.isEmpty() || !embeddings.isAvailable()) {
            signals.setIntentAlignmentScore(0.5); // neutral when no data
            return;
        }

        // For each JD sentence, find the best-matching resume bullet
        float totalAlignment = 0f;
        String topBullet = null;
        float topScore = 0f;

        for (String jdSentence : jdSentences) {
            float bestMatch = 0f;
            String bestBullet = null;

            for (String bullet : bullets) {
                float sim = embeddings.cosineSimilarity(jdSentence, bullet);
                if (sim > bestMatch) {
                    bestMatch = sim;
                    bestBullet = bullet;
                }
            }

            totalAlignment += bestMatch;
            if (bestMatch > topScore) {
                topScore = bestMatch;
                topBullet = bestBullet;
            }
        }

        float avgAlignment = jdSentences.isEmpty() ? 0.5f : totalAlignment / jdSentences.size();

        // Count how many JD sentences have at least one bullet above threshold
        long coveredSentences = jdSentences.stream()
            .filter(s -> bullets.stream().anyMatch(b -> embeddings.cosineSimilarity(s, b) >= INTENT_MATCH_THRESHOLD))
            .count();
        float coverageRatio = (float) coveredSentences / jdSentences.size();

        // Combined score: average alignment + coverage ratio
        double intentScore = (avgAlignment * 0.6 + coverageRatio * 0.4);
        signals.setIntentAlignmentScore(Math.min(1.0, intentScore));
        if (topBullet != null) signals.setTopAlignedBullet(
            topBullet.length() > 120 ? topBullet.substring(0, 120) + "…" : topBullet);

        log.debug("Intent alignment: avg={:.2f}, coverage={}/{}, score={:.2f}",
            avgAlignment, coveredSentences, jdSentences.size(), intentScore);
    }

    private void analyseDomainDepth(JobDescription jd, List<String> bullets, ResumeSignals signals) {
        List<String> requiredSkills = jd.getMustHaveSkills();
        if (requiredSkills == null || requiredSkills.isEmpty()) return;

        Map<String, Integer> skillBulletCount = new LinkedHashMap<>();
        List<String> shallowSkills = new ArrayList<>();

        for (String skill : requiredSkills) {
            String skillLower = skill.toLowerCase();
            long count = bullets.stream()
                .filter(b -> b.toLowerCase().contains(skillLower))
                .count();
            skillBulletCount.put(skill, (int) count);
            if (count == 1) shallowSkills.add(skill); // mentioned once = shallow
        }

        // Depth score: fraction of required skills with 2+ bullet mentions
        long deepSkills = skillBulletCount.values().stream().filter(c -> c >= 2).count();
        long foundSkills = skillBulletCount.values().stream().filter(c -> c >= 1).count();
        double depthScore = foundSkills == 0 ? 0.0 : (double) deepSkills / foundSkills;

        signals.setDomainDepthScore(depthScore);
        signals.setShallowSkills(shallowSkills.stream().limit(5).collect(Collectors.toList()));

        log.debug("Domain depth: deep={}/{}, shallow={}", deepSkills, foundSkills, shallowSkills);
    }

    /**
     * Extracts responsibility/task sentences from the JD.
     * Focuses on "what you'll do" content, not requirements lists.
     * Uses the trimmed JD text if available, otherwise constructs from must-haves.
     */
    private List<String> extractResponsibilitySentences(JobDescription jd) {
        String text = jd.getTrimmedText();
        if (text == null || text.isBlank()) {
            // Fallback: use must-have skills as proxy sentences
            return jd.getMustHaveSkills() == null ? List.of() :
                jd.getMustHaveSkills().stream()
                    .map(s -> "Experience with " + s)
                    .limit(8)
                    .collect(Collectors.toList());
        }

        // Split into sentences and filter for responsibility-like content
        // Responsibility sentences: start with verbs, contain action words, are 10-80 words
        List<String> sentences = new ArrayList<>();
        for (String line : text.split("[.!?\\n]")) {
            String trimmed = line.trim();
            if (trimmed.length() < 30 || trimmed.length() > 300) continue;
            String lower = trimmed.toLowerCase();
            // Skip pure skill lists (short, comma-separated)
            if (trimmed.split(",").length > 4 && trimmed.split("\\s+").length < 15) continue;
            // Keep lines that look like responsibilities
            if (lower.matches(".*\\b(build|design|develop|own|lead|drive|manage|create|" +
                    "implement|architect|scale|maintain|improve|collaborate|work|ensure|" +
                    "deliver|ship|define|establish|support|analyse|analyze|optimize)\\b.*")) {
                sentences.add(trimmed);
            }
        }

        return sentences.stream().limit(10).collect(Collectors.toList());
    }
}
