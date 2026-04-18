package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.ingest.ResumeIngestService.UnsupportedFileTypeException;
import com.resumestudio.reviewer.model.FeedbackReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;

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
    public void run(String jobId, byte[] resumeBytes, String jobDescription,
                    AsyncReviewController controller) {
        org.slf4j.MDC.put("jobId", jobId);
        try {
            MultipartFile resume = new ByteArrayMultipartFile(resumeBytes, "resume.pdf", "application/pdf");
            FeedbackReport report = pipeline.review(resume, jobDescription);
            reviewCache.put(resumeBytes, jobDescription, report);
            controller.writeJob(jobId, null, new AsyncReviewController.JobResult("DONE", report, null));
            log.info("[{}] Async review complete", jobId);
        } catch (UnsupportedFileTypeException e) {
            controller.writeJob(jobId, null, new AsyncReviewController.JobResult("ERROR", null, e.getMessage()));
        } catch (Exception e) {
            log.error("[{}] Async review failed", jobId, e);
            controller.writeJob(jobId, null, new AsyncReviewController.JobResult("ERROR", null, "Processing failed. Please try again."));
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Async("taskExecutor")
    public void run(String jobId, byte[] resumeBytes, String originalFilename, String contentType,
                    String jobDescription, AsyncReviewController controller) {
        org.slf4j.MDC.put("jobId", jobId);
        try {
            MultipartFile resume = new ByteArrayMultipartFile(resumeBytes, originalFilename, contentType);
            var result = pipeline.reviewFull(resume, jobDescription, null);
            reviewCache.put(resumeBytes, jobDescription, result.report());
            if (result.signals() != null) reviewCache.putSignals(resumeBytes, jobDescription, result.signals());
            if (result.resume() != null) reviewCache.putResume(resumeBytes, jobDescription, result.resume());
            controller.writeJob(jobId, null, new AsyncReviewController.JobResult("DONE", result.report(), null));
            log.info("[{}] Async review complete", jobId);
        } catch (UnsupportedFileTypeException e) {
            controller.writeJob(jobId, null, new AsyncReviewController.JobResult("ERROR", null, e.getMessage()));
        } catch (Exception e) {
            log.error("[{}] Async review failed", jobId, e);
            controller.writeJob(jobId, null, new AsyncReviewController.JobResult("ERROR", null, "Processing failed. Please try again."));
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    /** Minimal MultipartFile backed by a byte array — no temp file, safe across threads. */
    public static class ByteArrayMultipartFile implements MultipartFile {
        private final byte[] bytes;
        private final String originalFilename;
        private final String contentType;

        ByteArrayMultipartFile(byte[] bytes) {
            this(bytes, "resume.pdf", "application/pdf");
        }

        public ByteArrayMultipartFile(byte[] bytes, String originalFilename, String contentType) {
            this.bytes = bytes;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
        }

        @Override public String getName() { return "resume"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes == null || bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }
}
