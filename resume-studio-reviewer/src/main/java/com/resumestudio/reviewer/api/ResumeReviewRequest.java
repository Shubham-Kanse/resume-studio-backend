package com.resumestudio.reviewer.api;

import org.springframework.web.multipart.MultipartFile;

/**
 * Request for the 10-second resume review.
 */
public class ResumeReviewRequest {
    private MultipartFile resume;
    private String jobDescription;

    public MultipartFile getResume() {
        return resume;
    }

    public void setResume(MultipartFile resume) {
        this.resume = resume;
    }

    public String getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(String jobDescription) {
        this.jobDescription = jobDescription;
    }
}
