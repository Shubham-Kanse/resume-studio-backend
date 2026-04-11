package com.resumestudio.reviewer.skills;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic skill similarity using pre-computed embeddings (primary)
 * with MiniLM tokenizer overlap as fallback.
 */
@Component
public class SemanticSkillMatcher {

    private static final Logger log = LoggerFactory.getLogger(SemanticSkillMatcher.class);
    private static final float SIMILARITY_THRESHOLD = 0.82f;

    private final SkillEmbeddingIndex embeddingIndex;
    private HuggingFaceTokenizer tokenizer;
    private final Map<String, float[]> tokenEmbeddingCache = new ConcurrentHashMap<>();

    public SemanticSkillMatcher(SkillEmbeddingIndex embeddingIndex) {
        this.embeddingIndex = embeddingIndex;
    }

    private synchronized HuggingFaceTokenizer getTokenizer() {
        if (tokenizer == null) {
            try {
                tokenizer = HuggingFaceTokenizer.newInstance("sentence-transformers/all-MiniLM-L6-v2",
                    Map.of("padding", "true", "truncation", "true", "maxLength", "64"));
                log.info("SemanticSkillMatcher: MiniLM tokenizer loaded (fallback)");
            } catch (Exception e) {
                log.warn("SemanticSkillMatcher: tokenizer unavailable ({})", e.getMessage());
            }
        }
        return tokenizer;
    }

    public boolean isSemanticallySimilar(String jdSkill, String resumeSkill) {
        if (jdSkill == null || resumeSkill == null) return false;
        return similarity(jdSkill.toLowerCase().trim(), resumeSkill.toLowerCase().trim()) >= SIMILARITY_THRESHOLD;
    }

    public float similarity(String a, String b) {
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

    private float[] tokenEmbed(String text) {
        return tokenEmbeddingCache.computeIfAbsent(text, t -> {
            try {
                HuggingFaceTokenizer tok = getTokenizer();
                if (tok == null) return new float[0];
                Encoding enc = tok.encode(t);
                long[] ids = enc.getIds();
                float[] vec = new float[ids.length];
                for (int i = 0; i < ids.length; i++) vec[i] = ids[i];
                return normalise(vec);
            } catch (Exception e) {
                return new float[0];
            }
        });
    }

    private float cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0) return 0f;
        int len = Math.min(a.length, b.length);
        float dot = 0, na = 0, nb = 0;
        for (int i = 0; i < len; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        float denom = (float) (Math.sqrt(na) * Math.sqrt(nb));
        return denom == 0 ? 0f : dot / denom;
    }

    private float[] normalise(float[] v) {
        float norm = 0;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / norm;
        return out;
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
}
