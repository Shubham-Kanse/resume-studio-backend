package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.model.FeedbackReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST API for 10-second resume review.
 *
 * POST /api/review
 *   - resume: multipart file (PDF or DOCX)
 *   - jobDescription: raw JD text
 *
 * Returns: FeedbackReport with verdict, timeline, signals, and fixes
 */
@RestController
@RequestMapping("/api")
public class ResumeReviewerController {

    private static final Logger log = LoggerFactory.getLogger(ResumeReviewerController.class);

    private final ReviewerPipeline pipeline;

    public ResumeReviewerController(ReviewerPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping("/review")
    public ResponseEntity<FeedbackReport> review(
        @RequestParam("resume") MultipartFile resume,
        @RequestParam("jobDescription") String jobDescription
    ) {
        try {
            FeedbackReport report = pipeline.review(resume, jobDescription);
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
