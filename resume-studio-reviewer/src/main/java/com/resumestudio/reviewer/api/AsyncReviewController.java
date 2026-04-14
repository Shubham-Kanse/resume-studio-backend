package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ReviewerPipeline;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/review")
public class AsyncReviewController {

    private static final Logger log = LoggerFactory.getLogger(AsyncReviewController.class);

    public record JobResult(String status, FeedbackReport result, String error) {}

    final Cache<String, JobResult> jobs = Caffeine.newBuilder()
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .maximumSize(500)
        .build();

    private final ReviewerPipeline pipeline;
    private final ReviewCache reviewCache;
    private final AsyncReviewService asyncReviewService;

    public AsyncReviewController(ReviewerPipeline pipeline, ReviewCache reviewCache,
                                  AsyncReviewService asyncReviewService) {
        this.pipeline = pipeline;
        this.reviewCache = reviewCache;
        this.asyncReviewService = asyncReviewService;
    }

    @PostMapping("/async")
    public ResponseEntity<Map<String, String>> submitAsync(
        @RequestParam("resume") MultipartFile resume,
        @RequestParam("jobDescription") String jobDescription
    ) {
        if (resume == null || resume.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No resume file provided."));
        if (jobDescription == null || jobDescription.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Job description is required."));

        // Check cache first
        try {
            byte[] bytes = resume.getBytes();
            FeedbackReport cached = reviewCache.get(bytes, jobDescription);
            if (cached != null) {
                String jobId = UUID.randomUUID().toString().substring(0, 8);
                jobs.put(jobId, new JobResult("DONE", cached, null));
                return ResponseEntity.accepted().body(Map.of("jobId", jobId));
            }
        } catch (Exception ignored) {}

        String jobId = UUID.randomUUID().toString().substring(0, 8);
        jobs.put(jobId, new JobResult("PROCESSING", null, null));
        asyncReviewService.run(jobId, resume, jobDescription, jobs); // runs in separate bean → @Async works
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    }

    @GetMapping("/async/{jobId}")
    public ResponseEntity<?> pollAsync(@PathVariable String jobId) {
        JobResult job = jobs.getIfPresent(jobId);
        if (job == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found or expired."));
        return switch (job.status()) {
            case "PROCESSING" -> ResponseEntity.accepted().body(Map.of("status", "PROCESSING"));
            case "DONE" -> ResponseEntity.ok(job.result());
            default -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", job.error()));
        };
    }
}
