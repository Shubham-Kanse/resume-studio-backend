package com.resumestudio.reviewer.skills;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MiniLM-L6-v2 sentence embeddings via DJL TextEmbedding.
 * Downloads model from HuggingFace on first run, cached under ~/.djl.ai/
 * Falls back to Jaccard token overlap if model unavailable.
 */
@Component
public class SkillEmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillEmbeddingIndex.class);

    private ZooModel<String[], float[][]> model;
    private Predictor<String[], float[][]> predictor;
    private boolean available = false;

    @PostConstruct
    public void load() {
        try {
            log.info("SkillEmbeddingIndex: loading all-MiniLM-L6-v2...");
            Criteria<String[], float[][]> criteria = Criteria.builder()
                .optApplication(Application.NLP.TEXT_EMBEDDING)
                .setTypes(String[].class, float[][].class)
                .optModelUrls("djl://ai.djl.huggingface.onnxruntime/sentence-transformers/all-MiniLM-L6-v2")
                .optProgress(new ProgressBar())
                .build();
            model = criteria.loadModel();
            predictor = model.newPredictor();
            available = true;
            log.info("SkillEmbeddingIndex: all-MiniLM-L6-v2 ready");
        } catch (Exception e) {
            log.warn("SkillEmbeddingIndex: MiniLM unavailable ({}), falling back to Jaccard", e.getMessage());
        }
    }

    public float cosineSimilarity(String a, String b) {
        if (!available || a == null || b == null) return jaccard(a, b);
        try {
            float[][] embeddings = predictor.predict(new String[]{a, b});
            return dot(normalize(embeddings[0]), normalize(embeddings[1]));
        } catch (TranslateException e) {
            log.debug("Embedding failed: {}", e.getMessage());
            return jaccard(a, b);
        }
    }

    public float[] embed(String text) {
        if (!available || text == null) return null;
        try {
            return predictor.predict(new String[]{text})[0];
        } catch (TranslateException e) {
            return null;
        }
    }

    public boolean isAvailable() { return available; }

    private float[] normalize(float[] v) {
        float norm = 0f;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / norm;
        return out;
    }

    private float dot(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    private float jaccard(String a, String b) {
        if (a == null || b == null) return 0f;
        Set<String> sa = tokens(a), sb = tokens(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0f;
        long inter = sa.stream().filter(sb::contains).count();
        return (float) inter / (sa.size() + sb.size() - inter);
    }

    private Set<String> tokens(String s) {
        return new HashSet<>(Arrays.asList(
            s.toLowerCase().replaceAll("[^a-z0-9]", " ").trim().split("\\s+")));
    }
}
