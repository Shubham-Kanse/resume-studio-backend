package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ingest.ResumeIngestService.UnsupportedFileTypeException;
import com.resumestudio.reviewer.model.FeedbackReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ResumeReviewerController {

    private static final Logger log = LoggerFactory.getLogger(ResumeReviewerController.class);

    private final ReviewerPipeline pipeline;
    private final ReviewCache reviewCache;
    private final RateLimiterService rateLimiter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private redis.clients.jedis.JedisPool jedisPool;

    @org.springframework.beans.factory.annotation.Autowired
    private javax.sql.DataSource dataSource;

    public ResumeReviewerController(ReviewerPipeline pipeline, ReviewCache reviewCache,
                                     RateLimiterService rateLimiter) {
        this.pipeline = pipeline;
        this.reviewCache = reviewCache;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        boolean healthy = true;

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
        // Auth enforced by JwtAuthFilter — no inline check needed
        if (rateLimiter.isLimited(request)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please wait a minute before trying again.");
        }
        if (resume == null || resume.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "No resume file provided.");
        }
        if (jobDescription == null || jobDescription.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Job description is required.");
        }
        if (jobDescription.trim().length() < 50) {
            return error(HttpStatus.BAD_REQUEST, "Job description is too short. Paste the full JD or provide a URL.");
        }

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
        } catch (java.io.IOException e) {
            return error(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("Could not fetch job description from URL"))
                return error(HttpStatus.BAD_REQUEST, "Could not fetch the job description URL. The page may require login or be blocked. Paste the JD text directly.");
            if (msg.contains("cover letter"))
                return error(HttpStatus.BAD_REQUEST, msg);
            if (msg.contains("404") || msg.contains("not found") || msg.contains("no longer available"))
                return error(HttpStatus.BAD_REQUEST, "The job posting URL returned a 404 — the job may have been removed. Paste the JD text directly.");
            if (msg.contains("password"))
                return error(HttpStatus.BAD_REQUEST, "The uploaded PDF appears to be password-protected. Please upload an unlocked version.");
            if (msg.contains("Empty file"))
                return error(HttpStatus.BAD_REQUEST, "The uploaded file is empty.");
            if (msg.contains("timed out") || msg.contains("timeout") || msg.contains("SocketTimeoutException"))
                return error(HttpStatus.GATEWAY_TIMEOUT, "The request took too long to process. Try a shorter resume or paste the JD text directly.");
            log.error("Review failed", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong processing your resume. Please try again.");
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
