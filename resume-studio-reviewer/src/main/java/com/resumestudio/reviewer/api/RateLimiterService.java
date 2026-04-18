package com.resumestudio.reviewer.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared rate limiter — extracted from ResumeReviewerController and DeepDiveController (CQ4).
 * Limits by real client IP, reading X-Forwarded-For first.
 */
@Component
public class RateLimiterService {

    private static final int MAX_REQUESTS_PER_MINUTE = 10;
    private static final int MAX_PREVIEW_REQUESTS_PER_MINUTE = 30; // JD preview fires on debounce

    private final Cache<String, AtomicInteger> cache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(100_000)
        .build();

    private final Cache<String, AtomicInteger> previewCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(100_000)
        .build();

    public boolean isLimited(HttpServletRequest request) {
        return getCount(request, cache) > MAX_REQUESTS_PER_MINUTE;
    }

    public boolean isPreviewLimited(HttpServletRequest request) {
        return getCount(request, previewCache) > MAX_PREVIEW_REQUESTS_PER_MINUTE;
    }

    private int getCount(HttpServletRequest request, Cache<String, AtomicInteger> c) {
        String ip = Optional.ofNullable(request.getHeader("X-Forwarded-For"))
            .map(h -> h.split(",")[0].trim())
            .orElse(request.getRemoteAddr());
        return c.get(ip, k -> new AtomicInteger(0)).incrementAndGet();
    }
}
