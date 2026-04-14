package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.ingest.ResumeIngestService;
import com.resumestudio.reviewer.model.DeepDiveReport;
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
public class DeepDiveController {

    private static final Logger log = LoggerFactory.getLogger(DeepDiveController.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 10;

    private final Cache<String, AtomicInteger> rateLimiter = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .maximumSize(100_000)
        .build();

    private final ReviewerPipeline pipeline;
    private final ReviewCache reviewCache;

    public DeepDiveController(ReviewerPipeline pipeline, ReviewCache reviewCache) {
        this.pipeline = pipeline;
        this.reviewCache = reviewCache;
    }

    @PostMapping("/deepDive")
    public ResponseEntity<?> deepDive(
        @RequestParam("resume") MultipartFile resume,
        @RequestParam("jobDescription") String jobDescription,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        String ip = request.getRemoteAddr();
        if (isRateLimited(ip))
            return error(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please wait a minute before trying again.");
        if (resume == null || resume.isEmpty())
            return error(HttpStatus.BAD_REQUEST, "No resume file provided.");
        if (jobDescription == null || jobDescription.isBlank())
            return error(HttpStatus.BAD_REQUEST, "Job description is required.");
        if (jobDescription.trim().length() < 50)
            return error(HttpStatus.BAD_REQUEST, "Job description is too short. Paste the full JD or provide a URL.");

        String lowerFilename = resume.getOriginalFilename() != null ? resume.getOriginalFilename().toLowerCase() : "";
        if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg") || lowerFilename.endsWith(".png"))
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Please upload your resume as a PDF or Word document (.docx).");

        try {
            byte[] resumeBytes;
            try { resumeBytes = resume.getBytes(); } catch (java.io.IOException e) {
                return error(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
            }

            DeepDiveReport cached = reviewCache.getDeepDive(resumeBytes, jobDescription);
            if (cached != null) {
                log.info("DeepDive cache hit");
                return ResponseEntity.ok(cached);
            }

            DeepDiveReport report = pipeline.deepDive(resume, jobDescription);
            reviewCache.putDeepDive(resumeBytes, jobDescription, report);
            return ResponseEntity.ok(report);
        } catch (ResumeIngestService.UnsupportedFileTypeException e) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Deep dive failed", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
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
