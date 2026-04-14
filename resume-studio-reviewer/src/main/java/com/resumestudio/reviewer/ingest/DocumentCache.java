package com.resumestudio.reviewer.ingest;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * In-memory cache for parsed documents.
 * Key = CRC32 of file bytes (fast, sufficient for cache keying — not cryptographic).
 */
@Component
public class DocumentCache {

    private static final Logger log = LoggerFactory.getLogger(DocumentCache.class);
    private static final int MAX_FILE_SIZE_FOR_CACHE = 5 * 1024 * 1024;

    private final Cache<Long, RawDocument> cache = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build();

    public RawDocument get(byte[] fileBytes) {
        if (fileBytes.length > MAX_FILE_SIZE_FOR_CACHE) return null;
        return cache.getIfPresent(crc32(fileBytes));
    }

    public void put(byte[] fileBytes, RawDocument document) {
        if (fileBytes.length > MAX_FILE_SIZE_FOR_CACHE) return;
        cache.put(crc32(fileBytes), document);
    }

    private long crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }
}
