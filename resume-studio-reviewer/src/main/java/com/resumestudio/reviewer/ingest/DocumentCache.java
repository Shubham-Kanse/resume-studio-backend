package com.resumestudio.reviewer.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fix #6: Simple in-memory cache for parsed documents.
 * Prevents re-parsing the same file on retry/re-review.
 * Uses SHA-256 hash of file bytes as cache key.
 * LRU eviction with synchronized access.
 */
@Component
public class DocumentCache {

    private static final Logger log = LoggerFactory.getLogger(DocumentCache.class);
    private static final int MAX_CACHE_SIZE = 100;
    private static final int MAX_FILE_SIZE_FOR_CACHE = 5 * 1024 * 1024; // 5MB limit
    
    // Fix: Use LinkedHashMap for LRU, synchronize access
    private final Map<String, RawDocument> cache = new LinkedHashMap<>(MAX_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, RawDocument> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public synchronized RawDocument get(byte[] fileBytes) {
        if (fileBytes.length > MAX_FILE_SIZE_FOR_CACHE) {
            return null; // Don't cache large files
        }
        String hash = computeHash(fileBytes);
        return cache.get(hash);
    }

    public synchronized void put(byte[] fileBytes, RawDocument document) {
        if (fileBytes.length > MAX_FILE_SIZE_FOR_CACHE) {
            log.debug("Skipping cache for large file: {} bytes", fileBytes.length);
            return; // Don't cache large files
        }
        String hash = computeHash(fileBytes);
        cache.put(hash, document);
        log.debug("Cached document with hash: {} (cache size: {})", hash, cache.size());
    }

    public synchronized void clear() {
        cache.clear();
    }

    private String computeHash(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
