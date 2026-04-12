package com.resumestudio.reviewer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.net.URI;

/**
 * Wires a JedisPool from the REDIS_URL environment variable.
 * Supports rediss:// (TLS) — required for Upstash.
 *
 * Inject JedisPool wherever Redis is needed:
 *   try (Jedis jedis = jedisPool.getResource()) { jedis.set(...); }
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public JedisPool jedisPool(RedisProperties props) {
        if (props.getUrl() == null || props.getUrl().isBlank()) {
            log.warn("REDIS_URL not set — Redis caching disabled");
            return null;
        }
        try {
            URI uri = URI.create(props.getUrl());
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(10);
            config.setMaxIdle(5);
            config.setTestOnBorrow(true);
            JedisPool pool = new JedisPool(config, uri);
            log.info("Redis connected: {}", uri.getHost());
            return pool;
        } catch (Exception e) {
            log.warn("Redis connection failed ({}), caching disabled", e.getMessage());
            return null;
        }
    }
}
