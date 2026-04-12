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

    @Autowired(required = false)
    private JedisPool jedisPool;

    private final ObjectMapper mapper = new ObjectMapper();

    public FeedbackReport get(byte[] resumeBytes, String jdText) {
        if (jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            String key = buildKey(resumeBytes, jdText);
            String json = jedis.get(key);
            if (json == null) return null;
            log.debug("ReviewCache hit: {}", key.substring(PREFIX.length(), PREFIX.length() + 16));
            return mapper.readValue(json, FeedbackReport.class);
        } catch (Exception e) {
            log.debug("ReviewCache get failed: {}", e.getMessage());
            return null;
        }
    }

    public void put(byte[] resumeBytes, String jdText, FeedbackReport report) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            String key = buildKey(resumeBytes, jdText);
            String json = mapper.writeValueAsString(report);
            jedis.setex(key, TTL_SECONDS, json);
            log.debug("ReviewCache stored: {}", key.substring(PREFIX.length(), PREFIX.length() + 16));
        } catch (Exception e) {
            log.debug("ReviewCache put failed: {}", e.getMessage());
        }
    }

    private String buildKey(byte[] resumeBytes, String jdText) {
        return PREFIX + sha256(resumeBytes) + ":" + sha256(jdText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
