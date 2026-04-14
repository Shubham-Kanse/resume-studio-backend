package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ingest.ResumeIngestService.UnsupportedFileTypeException;
import com.resumestudio.reviewer.model.FeedbackReport;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api")
public class ResumeReviewerController {

    private static final Logger log = LoggerFactory.getLogger(ResumeReviewerController.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 10;

    // Rate limiter: IP → request count, auto-expires after 1 minute
    private final Cache<String, AtomicInteger> rateLimiter = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(100_000)
        .build();

    private final ReviewerPipeline pipeline;
    private final ReviewCache reviewCache;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private redis.clients.jedis.JedisPool jedisPool;

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    public ResumeReviewerController(ReviewerPipeline pipeline, ReviewCache reviewCache) {
        this.pipeline = pipeline;
        this.reviewCache = reviewCache;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        boolean healthy = true;

        // Redis
        if (jedisPool != null) {
            try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                status.put("redis", "UP");
            } catch (Exception e) {
                status.put("redis", "DOWN");
                healthy = false;
            }
        } else {
            status.put("redis", "DISABLED");
        }

        // DB
        try (java.sql.Connection conn = dataSource.getConnection()) {
            conn.isValid(1);
            status.put("db", "UP");
        } catch (Exception e) {
            status.put("db", "DOWN");
            healthy = false;
        }

        status.put("status", healthy ? "UP" : "DEGRADED");
        return ResponseEntity.status(healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(status);
    }

    @PostMapping("/review")
    public ResponseEntity<?> review(
        @RequestParam("resume") MultipartFile resume,
        @RequestParam("jobDescription") String jobDescription,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        // Rate limiting
        String ip = request.getRemoteAddr();
        if (isRateLimited(ip)) {
            return error(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Please wait a minute before trying again.");
        }
        // Input validation — return user-facing messages, not stack traces
        if (resume == null || resume.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "No resume file provided.");
        }
        if (jobDescription == null || jobDescription.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Job description is required.");
        }
        if (jobDescription.trim().length() < 50) {
            return error(HttpStatus.BAD_REQUEST,
                "Job description is too short. Paste the full JD or provide a URL.");
        }

        // Image file detection
        String lowerFilename = resume.getOriginalFilename() != null
            ? resume.getOriginalFilename().toLowerCase() : "";
        if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")
                || lowerFilename.endsWith(".png") || lowerFilename.endsWith(".gif")
                || lowerFilename.endsWith(".webp")) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "You uploaded an image file. Please upload your resume as a PDF or Word document (.docx).");
        }

        try {
            String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);
            org.slf4j.MDC.put("requestId", requestId);

            byte[] resumeBytes;
            try { resumeBytes = resume.getBytes(); } catch (java.io.IOException e) {
                return error(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
            }

            // Idempotency check — same resume content + same JD = cached result
            FeedbackReport cached = reviewCache.get(resumeBytes, jobDescription);
            if (cached != null) {
                log.info("[{}] Cache hit — returning cached review", requestId);
                return ResponseEntity.ok(cached);
            }

            FeedbackReport report = pipeline.review(resume, jobDescription);
            reviewCache.put(resumeBytes, jobDescription, report);
            return ResponseEntity.ok(report);

        } catch (UnsupportedFileTypeException e) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());

        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Could not fetch job description from URL")) {
                return error(HttpStatus.BAD_REQUEST,
                    "Could not fetch the job description URL. The page may require login or be blocked. Paste the JD text directly.");
            }
            if (msg.contains("cover letter")) {
                return error(HttpStatus.BAD_REQUEST, msg);
            }
            if (msg.contains("404") || msg.contains("not found") || msg.contains("no longer available")) {
                return error(HttpStatus.BAD_REQUEST,
                    "The job posting URL returned a 404 — the job may have been removed. Paste the JD text directly.");
            }
            if (msg.contains("password")) {
                return error(HttpStatus.BAD_REQUEST,
                    "The uploaded PDF appears to be password-protected. Please upload an unlocked version.");
            }
            if (msg.contains("Empty file")) {
                return error(HttpStatus.BAD_REQUEST, "The uploaded file is empty.");
            }
            if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("SocketTimeoutException")) {
                return error(HttpStatus.GATEWAY_TIMEOUT,
                    "The request took too long to process. Try a shorter resume or paste the JD text directly.");
            }
            log.error("Review failed", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong processing your resume. Please try again.");
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private boolean isRateLimited(String ip) {
        AtomicInteger count = rateLimiter.get(ip, k -> new AtomicInteger(0));
        return count.incrementAndGet() > MAX_REQUESTS_PER_MINUTE;
    }
}
