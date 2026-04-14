package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.ingest.ResumeIngestService.UnsupportedFileTypeException;
import com.resumestudio.reviewer.model.FeedbackReport;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AsyncReviewService {

    private static final Logger log = LoggerFactory.getLogger(AsyncReviewService.class);

    private final ReviewerPipeline pipeline;
    private final ReviewCache reviewCache;

    public AsyncReviewService(ReviewerPipeline pipeline, ReviewCache reviewCache) {
        this.pipeline = pipeline;
        this.reviewCache = reviewCache;
    }

    @Async("taskExecutor")
    public void run(String jobId, MultipartFile resume, String jobDescription,
                    Cache<String, AsyncReviewController.JobResult> jobs) {
        try {
            FeedbackReport report = pipeline.review(resume, jobDescription);
            try { reviewCache.put(resume.getBytes(), jobDescription, report); } catch (Exception ignored) {}
            jobs.put(jobId, new AsyncReviewController.JobResult("DONE", report, null));
            log.info("[{}] Async review complete", jobId);
        } catch (UnsupportedFileTypeException e) {
            jobs.put(jobId, new AsyncReviewController.JobResult("ERROR", null, e.getMessage()));
        } catch (Exception e) {
            log.error("[{}] Async review failed", jobId, e);
            jobs.put(jobId, new AsyncReviewController.JobResult("ERROR", null, "Processing failed. Please try again."));
        }
    }
}
