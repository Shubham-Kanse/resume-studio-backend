package com.resumestudio.reviewer.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Semantic skill similarity using pre-computed embeddings (primary)
 * with MiniLM tokenizer overlap as fallback.
 */
@Component
public class SemanticSkillMatcher {

    private static final Logger log = LoggerFactory.getLogger(SemanticSkillMatcher.class);
    private static final float DEFAULT_THRESHOLD = 0.82f;

    private final SkillEmbeddingIndex embeddingIndex;
    private float semanticMatchThreshold = DEFAULT_THRESHOLD;

    public SemanticSkillMatcher(SkillEmbeddingIndex embeddingIndex) {
        this.embeddingIndex = embeddingIndex;
    }

    @Value("${reviewer.skills.semantic.threshold:0.82}")
    public void configureSemanticMatchThreshold(float threshold) {
        setSemanticMatchThreshold(threshold);
    }

    public boolean isSemanticallySimilar(String jdSkill, String resumeSkill) {
        if (jdSkill == null || resumeSkill == null) return false;
        return similarity(jdSkill.toLowerCase().trim(), resumeSkill.toLowerCase().trim()) >= semanticMatchThreshold;
    }

    public float similarity(String a, String b) {
        if (a == null || b == null) return 0f;
        if (a.equals(b)) return 1.0f;

        if (embeddingIndex.isAvailable()) {
            float sim = embeddingIndex.cosineSimilarity(a, b);
            if (sim >= 0) return sim;
            return 0f; // not in index — don't fall back to tokenizer
        }

        // Embedding index unavailable — fall back to Jaccard token overlap.
        // Note: a tokenizer-ID-based cosine approach was removed here because
        // raw token IDs are not semantic vectors and produce spurious near-1
        // similarities for unrelated terms.
        return jaccard(a, b);
    }

    private float jaccard(String a, String b) {
        Set<String> sa = tokens(a), sb = tokens(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0f;
        long inter = sa.stream().filter(sb::contains).count();
        return (float) inter / (sa.size() + sb.size() - inter);
    }

    private Set<String> tokens(String s) {
        return new HashSet<>(Arrays.asList(s.toLowerCase().replaceAll("[^a-z0-9]", " ").trim().split("\\s+")));
    }

    /**
     * Find best semantic match for JD skill among resume skills.
     */
    public MatchResult findBestMatch(String jdSkill, List<com.resumestudio.reviewer.model.Skill> resumeSkills) {
        if (jdSkill == null || resumeSkills == null || resumeSkills.isEmpty()) return null;
        
        float bestScore = 0f;
        com.resumestudio.reviewer.model.Skill bestSkill = null;
        
        for (com.resumestudio.reviewer.model.Skill skill : resumeSkills) {
            float score = similarity(jdSkill, skill.getRawName());
            if (score > bestScore) {
                bestScore = score;
                bestSkill = skill;
            }
        }

        return bestScore >= semanticMatchThreshold ? new MatchResult(bestSkill, bestScore) : null;
    }

    public float getSemanticMatchThreshold() {
        return semanticMatchThreshold;
    }

    public void setSemanticMatchThreshold(float threshold) {
        if (Float.isNaN(threshold) || threshold <= 0f || threshold > 1f) {
            log.warn("SemanticSkillMatcher: invalid threshold {}. Using default {}", threshold, DEFAULT_THRESHOLD);
            this.semanticMatchThreshold = DEFAULT_THRESHOLD;
            return;
        }
        this.semanticMatchThreshold = threshold;
    }

    public static class MatchResult {
        private final com.resumestudio.reviewer.model.Skill skill;
        private final float score;

        public MatchResult(com.resumestudio.reviewer.model.Skill skill, float score) {
            this.skill = skill;
            this.score = score;
        }

        public com.resumestudio.reviewer.model.Skill getSkill() { return skill; }
        public float getScore() { return score; }
    }
}
