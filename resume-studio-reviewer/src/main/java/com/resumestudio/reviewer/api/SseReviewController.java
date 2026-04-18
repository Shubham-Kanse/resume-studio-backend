package com.resumestudio.reviewer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.auth.SupabaseJwtVerifier;
import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.model.FeedbackReport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSE-based review endpoint.
 *
 * POST /api/review/stream  — starts a review and streams progress events:
 *   event: progress  data: {"stage":"Matching skills…","elapsed":3}
 *   event: done      data: <FeedbackReport JSON>
 *   event: error     data: {"error":"…"}
 *
 * The client holds a single long-lived connection instead of polling.
 * Falls back gracefully: if SSE fails mid-stream, client can retry via /api/review/async.
 */
@RestController
@RequestMapping("/api/review")
public class SseReviewController {

    private static final Logger log = LoggerFactory.getLogger(SseReviewController.class);
    private static final long SSE_TIMEOUT_MS = 180_000; // 3 min max

    private final ReviewerPipeline pipeline;
    private final ReviewCache reviewCache;
    private final ReviewJobRepository jobRepo;
    private final SupabaseJwtVerifier verifier;
    private final RateLimiterService rateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SseReviewController(ReviewerPipeline pipeline, ReviewCache reviewCache,
                                ReviewJobRepository jobRepo, SupabaseJwtVerifier verifier,
                                RateLimiterService rateLimiter) {
        this.pipeline = pipeline;
        this.reviewCache = reviewCache;
        this.jobRepo = jobRepo;
        this.verifier = verifier;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @RequestParam("resume") MultipartFile resume,
        @RequestParam("jobDescription") String jobDescription,
        HttpServletRequest request
    ) {
        // Rate limit
        if (rateLimiter.isLimited(request)) {
            SseEmitter err = new SseEmitter(0L);
            sendError(err, "Too many requests. Please wait a minute before trying again.", 429);
            return err;
        }
        // Auth
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            SseEmitter err = new SseEmitter(0L);
            sendError(err, "Authentication required.", 401);
            return err;
        }
        SupabaseJwtVerifier.UserClaims claims;
        try {
            claims = verifier.verify(authHeader.substring(7));
        } catch (Exception e) {
            SseEmitter err = new SseEmitter(0L);
            sendError(err, "Invalid or expired token.", 401);
            return err;
        }
        if (resume == null || resume.isEmpty()) {
            SseEmitter err = new SseEmitter(0L);
            sendError(err, "No resume file provided.", 400);
            return err;
        }
        if (jobDescription == null || jobDescription.isBlank()) {
            SseEmitter err = new SseEmitter(0L);
            sendError(err, "Job description is required.", 400);
            return err;
        }

        byte[] resumeBytes;
        try { resumeBytes = resume.getBytes(); }
        catch (IOException e) {
            SseEmitter err = new SseEmitter(0L);
            sendError(err, "Could not read the uploaded file.", 400);
            return err;
        }

        // Create a DB record immediately so the result survives connection drops
        String jobId = java.util.UUID.randomUUID().toString();
        persistJob(jobId, claims.userId(), "PROCESSING", null, null);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        final String origFilename = resume.getOriginalFilename();
        final String contentType = resume.getContentType();

        executor.submit(() -> {
            org.slf4j.MDC.put("sse", "stream");
            try {
                // Send job ID immediately so client can save it for outcome reporting
                try {
                    emitter.send(SseEmitter.event()
                        .name("jobId")
                        .data(mapper.writeValueAsString(Map.of("jobId", jobId))));
                } catch (IOException ignored) {}

                // Cache check — instant done event
                FeedbackReport cached = reviewCache.get(resumeBytes, jobDescription);
                if (cached != null) {
                    persistJob(jobId, claims.userId(), "DONE", cached, null);
                    sendProgress(emitter, "Returning cached result…", 0);
                    sendDone(emitter, cached);
                    return;
                }

                // Progress events while pipeline runs
                String[] stages = {
                    "Parsing resume…",
                    "Matching skills…",
                    "Evaluating signals…",
                    "Simulating recruiter…",
                    "Generating feedback…"
                };
                int[] elapsed = {0};
                var stageThread = Thread.ofVirtual().start(() -> {
                    int idx = 0;
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            sendProgress(emitter, stages[Math.min(idx, stages.length - 1)], elapsed[0]);
                            Thread.sleep(2000);
                            elapsed[0] += 2;
                            if (elapsed[0] % 8 == 0) idx++;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                });

                FeedbackReport report;
                try {
                    var mpFile = new AsyncReviewService.ByteArrayMultipartFile(resumeBytes,
                        origFilename != null ? origFilename : "resume.pdf",
                        contentType != null ? contentType : "application/pdf");
                    var result = pipeline.reviewFull(mpFile, jobDescription, claims.userId());
                    report = result.report();
                    if (result.signals() != null) reviewCache.putSignals(resumeBytes, jobDescription, result.signals());
                    if (result.resume() != null) {
                        reviewCache.putResume(resumeBytes, jobDescription, result.resume());
                        // Also cache by text fingerprint so re-saved files hit the cache
                        var r = result.resume();
                        StringBuilder fp = new StringBuilder();
                        if (r.getCandidateName() != null) fp.append(r.getCandidateName());
                        if (r.getSummaryText() != null) fp.append(r.getSummaryText());
                        if (r.getExperience() != null) r.getExperience().stream().limit(3)
                            .forEach(e -> { if (e.getTitle() != null) fp.append(e.getTitle()); if (e.getCompany() != null) fp.append(e.getCompany()); });
                        String fingerprint = fp.toString().trim();
                        if (!fingerprint.isBlank()) reviewCache.putByText(fingerprint, jobDescription, report);
                    }
                } finally {
                    stageThread.interrupt();
                }

                reviewCache.put(resumeBytes, jobDescription, report);
                persistJob(jobId, claims.userId(), "DONE", report, null);
                sendDone(emitter, report);

            } catch (Exception e) {
                log.error("SSE review failed", e);
                persistJob(jobId, claims.userId(), "ERROR", null, e.getMessage());
                sendError(emitter, "Processing failed. Please try again.", 500);
            } finally {
                org.slf4j.MDC.clear();
            }
        });

        return emitter;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void persistJob(String jobId, String userId, String status, FeedbackReport report, String error) {
        try {
            ReviewJobEntity entity = jobRepo.findById(jobId).orElseGet(() -> new ReviewJobEntity(jobId, userId));
            entity.setStatus(status);
            if ("DONE".equals(status) && report != null) {
                entity.setResultJson(mapper.writeValueAsString(report));
                entity.setCompletedAt(java.time.Instant.now());
            }
            if ("ERROR".equals(status)) {
                entity.setErrorMessage(error);
                entity.setCompletedAt(java.time.Instant.now());
            }
            jobRepo.save(entity);
        } catch (Exception ex) {
            log.warn("Failed to persist SSE job {}: {}", jobId, ex.getMessage());
        }
    }

    private void sendProgress(SseEmitter emitter, String stage, int elapsed) {
        try {
            emitter.send(SseEmitter.event()
                .name("progress")
                .data(mapper.writeValueAsString(Map.of("stage", stage, "elapsed", elapsed))));
        } catch (IOException ignored) { /* client disconnected */ }
    }

    private void sendDone(SseEmitter emitter, FeedbackReport report) {
        try {
            emitter.send(SseEmitter.event().name("done").data(mapper.writeValueAsString(report)));
            emitter.complete();
        } catch (IOException ignored) {
            emitter.completeWithError(new RuntimeException("Client disconnected"));
        }
    }

    private void sendError(SseEmitter emitter, String message, int status) {
        try {
            emitter.send(SseEmitter.event()
                .name("error")
                .data(mapper.writeValueAsString(Map.of("error", message, "status", status))));
            emitter.complete();
        } catch (IOException ignored) {
            emitter.completeWithError(new RuntimeException(message));
        }
    }
}
