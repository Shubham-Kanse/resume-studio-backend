package com.resumestudio.reviewer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.auth.SupabaseJwtVerifier;
import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.model.FeedbackReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Async review: submit → poll.
 * Jobs are stored in Redis (fast) AND Postgres (durable).
 * On poll, Redis is checked first; on miss, DB is the fallback.
 */
@RestController
@RequestMapping("/api/review")
public class AsyncReviewController {

    private static final Logger log = LoggerFactory.getLogger(AsyncReviewController.class);
    private static final int JOB_TTL_SECONDS = 900; // 15 min Redis TTL

    public record JobResult(String status, FeedbackReport result, String error) {}

    private final ReviewerPipeline pipeline;
    private final ReviewCache reviewCache;
    private final AsyncReviewService asyncReviewService;
    private final ReviewJobRepository jobRepo;
    private final JedisPool jedisPool;
    private final SupabaseJwtVerifier verifier;
    private final ObjectMapper mapper = new ObjectMapper();

    public AsyncReviewController(ReviewerPipeline pipeline, ReviewCache reviewCache,
                                  AsyncReviewService asyncReviewService,
                                  ReviewJobRepository jobRepo,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false) JedisPool jedisPool,
                                  SupabaseJwtVerifier verifier) {
        this.pipeline = pipeline;
        this.reviewCache = reviewCache;
        this.asyncReviewService = asyncReviewService;
        this.jobRepo = jobRepo;
        this.jedisPool = jedisPool;
        this.verifier = verifier;
    }

    @PostMapping("/async")
    public ResponseEntity<Map<String, String>> submitAsync(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @RequestParam("resume") MultipartFile resume,
        @RequestParam("jobDescription") String jobDescription
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required."));

        SupabaseJwtVerifier.UserClaims claims;
        try {
            claims = verifier.verify(authHeader.substring(7));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid or expired token."));
        }

        if (resume == null || resume.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No resume file provided."));
        if (jobDescription == null || jobDescription.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Job description is required."));

        byte[] resumeBytes;
        try { resumeBytes = resume.getBytes(); }
        catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read the uploaded file."));
        }

        // Cache hit → instant DONE
        FeedbackReport cached = reviewCache.get(resumeBytes, jobDescription);
        if (cached != null) {
            String jobId = UUID.randomUUID().toString();
            writeJob(jobId, claims.userId(), new JobResult("DONE", cached, null));
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        }

        String jobId = UUID.randomUUID().toString();
        writeJob(jobId, claims.userId(), new JobResult("PROCESSING", null, null));
        asyncReviewService.run(jobId, resumeBytes,
            resume.getOriginalFilename() != null ? resume.getOriginalFilename() : "resume.pdf",
            resume.getContentType() != null ? resume.getContentType() : "application/pdf",
            jobDescription, this);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping("/async/{jobId}")
    public ResponseEntity<?> pollAsync(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @PathVariable String jobId
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required."));
        try { verifier.verify(authHeader.substring(7)); }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid or expired token."));
        }

        JobResult job = readJob(jobId);
        if (job == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found or expired."));

        return switch (job.status()) {
            case "PROCESSING" -> ResponseEntity.accepted().body(Map.of("status", "PROCESSING"));
            case "DONE" -> {
                // Null out result_json in DB after client fetches — saves storage
                nullifyResultJson(jobId);
                yield ResponseEntity.ok(job.result());
            }
            case "EXPIRED" -> ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", job.error() != null ? job.error() : "Result already retrieved. Please run a new review."));
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("error", job.error() != null ? job.error() : "Processing failed."));
        };
    }

    // ── Write/read with Redis fast-path + DB fallback ─────────────────────────

    public void writeJob(String jobId, String userId, JobResult result) {
        // 1. Write to Redis (fast polling path)
        writeToRedis(jobId, result);

        // 2. Upsert to DB (durable)
        try {
            ReviewJobEntity entity = jobRepo.findById(jobId).orElseGet(() -> new ReviewJobEntity(jobId, userId));
            entity.setStatus(result.status());
            if ("DONE".equals(result.status()) && result.result() != null) {
                entity.setResultJson(mapper.writeValueAsString(result.result()));
                entity.setCompletedAt(Instant.now());
            }
            if ("ERROR".equals(result.status())) {
                entity.setErrorMessage(result.error());
                entity.setCompletedAt(Instant.now());
            }
            jobRepo.save(entity);
        } catch (Exception e) {
            log.error("Failed to persist job {} to DB", jobId, e);
        }
    }

    private JobResult readJob(String jobId) {
        // 1. Redis fast path
        JobResult r = readFromRedis(jobId);
        if (r != null) return r;

        // 2. DB fallback
        try {
            return jobRepo.findById(jobId).map(entity -> {
                try {
                    if ("DONE".equals(entity.getStatus()) && entity.getResultJson() == null) {
                        // Already fetched and nulled — client retried after result was consumed
                        return new JobResult("EXPIRED", null, "Result already retrieved. Please run a new review.");
                    }
                    FeedbackReport report = null;
                    if ("DONE".equals(entity.getStatus()) && entity.getResultJson() != null) {
                        report = mapper.readValue(entity.getResultJson(), FeedbackReport.class);
                    }
                    return new JobResult(entity.getStatus(), report, entity.getErrorMessage());
                } catch (Exception e) {
                    log.error("Failed to deserialize job {} from DB", jobId, e);
                    return null;
                }
            }).orElse(null);
        } catch (Exception e) {
            log.error("DB read failed for job {}", jobId, e);
            return null;
        }
    }

    private void nullifyResultJson(String jobId) {
        try {
            jobRepo.findById(jobId).ifPresent(entity -> {
                if (entity.getFetchedAt() == null) {
                    entity.setFetchedAt(Instant.now());
                    entity.setResultJson(null);
                    jobRepo.save(entity);
                }
            });
        } catch (Exception e) {
            log.warn("Failed to nullify result_json for job {}: {}", jobId, e.getMessage());
        }
    }

    private void writeToRedis(String jobId, JobResult result) {
        if (jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex("async-job:" + jobId, JOB_TTL_SECONDS, mapper.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("Redis write failed for job {}: {}", jobId, e.getMessage());
        }
    }

    private JobResult readFromRedis(String jobId) {
        if (jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            String raw = jedis.get("async-job:" + jobId);
            if (raw == null) return null;
            return mapper.readValue(raw, JobResult.class);
        } catch (Exception e) {
            log.warn("Redis read failed for job {}: {}", jobId, e.getMessage());
            return null;
        }
    }
}
