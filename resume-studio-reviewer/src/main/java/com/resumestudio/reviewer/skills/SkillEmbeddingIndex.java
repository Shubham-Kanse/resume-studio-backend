package com.resumestudio.reviewer.skills;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads the pre-computed skill embedding index (skill-embeddings-v1.bin).
 *
 * Binary format (little-endian):
 *   Magic:   4 bytes = 0x454D4244 ('DBME' LE)
 *   Version: 1 byte
 *   Dim:     2 bytes = embedding dimension (768)
 *   Count:   4 bytes = number of entries
 *   Entries: { label_len(2) + label(N bytes UTF-8) + embedding(dim × float32 LE) }
 *
 * All embeddings are L2-normalised → cosine similarity = dot product.
 */
@Component
public class SkillEmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillEmbeddingIndex.class);
    private static final int MAGIC = 0x454D4244;
    private static final String INDEX_PATH = "taxonomy/skill-embeddings-v1.bin";

    private String[] labels;
    private float[][] embeddings;
    private int dim;
    private Map<String, Integer> labelIndex;
    private boolean available = false;

    @PostConstruct
    public void load() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(INDEX_PATH)) {
            if (is == null) {
                log.warn("SkillEmbeddingIndex: {} not found on classpath", INDEX_PATH);
                return;
            }
            DataInputStream dis = new DataInputStream(new BufferedInputStream(is));

            int magic = readIntLE(dis);
            if (magic != MAGIC) throw new IOException("Invalid magic: 0x" + Integer.toHexString(magic));
            dis.readByte(); // version
            dim = readShortLE(dis);
            int count = readIntLE(dis);
            log.info("SkillEmbeddingIndex: loading {} entries × {} dims...", count, dim);

            labels = new String[count];
            embeddings = new float[count][dim];
            labelIndex = new HashMap<>(count * 2);
            byte[] floatBuf = new byte[dim * 4];

            for (int i = 0; i < count; i++) {
                int labelLen = readShortLE(dis);
                byte[] labelBytes = dis.readNBytes(labelLen);
                String label = new String(labelBytes, StandardCharsets.UTF_8);
                labels[i] = label;
                labelIndex.put(label.toLowerCase().trim(), i);

                dis.readFully(floatBuf);
                ByteBuffer bb = ByteBuffer.wrap(floatBuf).order(ByteOrder.LITTLE_ENDIAN);
                bb.asFloatBuffer().get(embeddings[i]);
            }

            available = true;
            if (labels.length > 1) {
                float norm = 0;
                for (float v : embeddings[1]) norm += v * v;
                log.info("SkillEmbeddingIndex: loaded {} embeddings (dim={}, sample norm={}, label[1]='{}')",
                    count, dim, String.format("%.4f", Math.sqrt(norm)), labels[1]);
            }
        } catch (Exception e) {
            log.warn("SkillEmbeddingIndex: failed to load ({})", e.getMessage());
        }
    }

    /** Cosine similarity between two skill labels. Returns -1 if either is not in the index. */
    public float cosineSimilarity(String labelA, String labelB) {
        if (!available) return -1f;
        float[] a = getEmbedding(labelA);
        float[] b = getEmbedding(labelB);
        if (a == null || b == null) return -1f;
        return dot(a, b);
    }

    /** Returns the pre-computed embedding for a label, or null if not found. */
    public float[] getEmbedding(String label) {
        if (!available || label == null) return null;
        Integer idx = labelIndex.get(label.toLowerCase().trim());
        return idx != null ? embeddings[idx] : null;
    }

    /** Finds the top-K most similar labels to the query. */
    public List<Map.Entry<String, Float>> findMostSimilar(String query, int topK) {
        if (!available) return List.of();
        float[] qEmb = getEmbedding(query);
        if (qEmb == null) return List.of();

        float[] scores = new float[labels.length];
        for (int i = 0; i < labels.length; i++) {
            scores[i] = dot(qEmb, embeddings[i]);
        }

        Integer[] indices = new Integer[labels.length];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> Float.compare(scores[b], scores[a]));

        List<Map.Entry<String, Float>> result = new ArrayList<>(topK);
        for (int i = 0; i < Math.min(topK, indices.length); i++) {
            int idx = indices[i];
            result.add(Map.entry(labels[idx], scores[idx]));
        }
        return result;
    }

    public boolean isAvailable() { return available; }
    public int getDim() { return dim; }

    private float dot(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    private static int readIntLE(DataInputStream dis) throws IOException {
        int b0 = dis.readUnsignedByte();
        int b1 = dis.readUnsignedByte();
        int b2 = dis.readUnsignedByte();
        int b3 = dis.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readShortLE(DataInputStream dis) throws IOException {
        int b0 = dis.readUnsignedByte();
        int b1 = dis.readUnsignedByte();
        return b0 | (b1 << 8);
    }
}
