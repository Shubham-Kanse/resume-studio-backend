package com.resumestudio.reviewer;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Spring Cache abstraction backed by Caffeine (W-TinyLFU eviction).
 *
 * Named caches:
 *   jd-parse      — parsed JobDescription objects. Same JD text → skip all NLP.
 *   skill-match   — SkillMatchResult lists. Same JD skills + resume skills → skip embeddings.
 *   onet          — O*NET occupation lookups (external API, 90-day TTL).
 *   esco-equiv    — ESCO equivalence pairs (external API, 30-day TTL).
 *
 * Metrics exposed via Actuator at /actuator/metrics/cache.gets etc.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(java.util.List.of("jd-parse", "skill-match", "onet", "esco-equiv"));

        // Default spec — overridden per-cache below
        manager.setCaffeine(Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats()); // enables Micrometer metrics

        // Per-cache specs
        manager.registerCustomCache("jd-parse",
            Caffeine.newBuilder()
                .maximumSize(200)
                .expireAfterWrite(2, TimeUnit.HOURS)
                .recordStats()
                .build());

        manager.registerCustomCache("skill-match",
            Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(4, TimeUnit.HOURS)
                .recordStats()
                .build());

        manager.registerCustomCache("onet",
            Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(90, TimeUnit.DAYS)
                .recordStats()
                .build());

        manager.registerCustomCache("esco-equiv",
            Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(30, TimeUnit.DAYS)
                .recordStats()
                .build());

        return manager;
    }
}
