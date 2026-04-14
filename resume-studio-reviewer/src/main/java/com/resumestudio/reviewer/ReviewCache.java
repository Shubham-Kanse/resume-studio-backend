package com.resumestudio.reviewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.reviewer.model.FeedbackReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.security.MessageDigest;

/**
 * Idempotency cache for review results.
 *
 * Cache key = SHA-256(resumeBytes) + ":" + SHA-256(jdText)
 * TTL = 24 hours
 *
 * Same resume content + same JD → cache hit, no AI call.
 * Resume file changes (even 1 byte) → cache miss, full pipeline runs.
 *
 * Falls back gracefully if Redis is unavailable.
 */
@Component
public class ReviewCache {

    private static final Logger log = LoggerFactory.getLogger(ReviewCache.class);
    private static final int TTL_SECONDS = 86_400; // 24 hours
    private static final String PREFIX = "review:v1:";
    private static final String DEEP_DIVE_PREFIX = "deepdive:v1:";

    @Autowired(required = false)
    private JedisPool jedisPool;

    private final ObjectMapper mapper = new ObjectMapper();

    public FeedbackReport get(byte[] resumeBytes, String jdText) {
        return getAs(PREFIX, resumeBytes, jdText, FeedbackReport.class);
    }

    public void put(byte[] resumeBytes, String jdText, FeedbackReport report) {
        putAs(PREFIX, resumeBytes, jdText, report);
    }

    public com.resumestudio.reviewer.model.DeepDiveReport getDeepDive(byte[] resumeBytes, String jdText) {
        return getAs(DEEP_DIVE_PREFIX, resumeBytes, jdText, com.resumestudio.reviewer.model.DeepDiveReport.class);
    }

    public void putDeepDive(byte[] resumeBytes, String jdText, com.resumestudio.reviewer.model.DeepDiveReport report) {
        putAs(DEEP_DIVE_PREFIX, resumeBytes, jdText, report);
    }

    private <T> T getAs(String prefix, byte[] resumeBytes, String jdText, Class<T> type) {
        if (jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            String key = buildKey(prefix, resumeBytes, jdText);
            String json = jedis.get(key);
            if (json == null) return null;
            log.debug("Cache hit [{}]: {}", prefix.replace(":v1:", ""), key.substring(prefix.length(), prefix.length() + 16));
            return mapper.readValue(json, type);
        } catch (Exception e) {
            log.debug("Cache get failed: {}", e.getMessage());
            return null;
        }
    }

    private void putAs(String prefix, byte[] resumeBytes, String jdText, Object report) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String key = buildKey(prefix, resumeBytes, jdText);
            String json = mapper.writeValueAsString(report);
            jedis.setex(key, TTL_SECONDS, json);
            log.debug("Cache stored [{}]: {}", prefix.replace(":v1:", ""), key.substring(prefix.length(), prefix.length() + 16));
        } catch (Exception e) {
            log.debug("Cache put failed: {}", e.getMessage());
        }
    }

    private String buildKey(String prefix, byte[] resumeBytes, String jdText) {
        return prefix + sha256(resumeBytes) + ":" + sha256(jdText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String sha256(byte[] input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
