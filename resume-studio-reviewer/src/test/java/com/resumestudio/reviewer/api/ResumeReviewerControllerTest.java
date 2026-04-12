package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.ingest.ResumeIngestService.UnsupportedFileTypeException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeReviewerControllerTest {

    private ReviewerPipeline pipeline;
    private ResumeReviewerController controller;
    private HttpServletRequest mockRequest;

    @BeforeEach
    void setUp() {
        pipeline = mock(ReviewerPipeline.class);
        controller = new ResumeReviewerController(pipeline, mock(com.resumestudio.reviewer.ReviewCache.class));
        mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    private MockMultipartFile pdf(String name) {
        return new MockMultipartFile("resume", name, "application/pdf", new byte[]{1, 2, 3});
    }

    private static final String VALID_JD = "We are looking for a Senior Java Engineer with 5+ years of experience in Spring Boot, Kubernetes, and AWS.";

    @Test void nullResume_returns400() {
        var r = controller.review(null, VALID_JD, mockRequest);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertTrue(r.getBody().toString().contains("No resume file"));
    }

    @Test void emptyResume_returns400() {
        var file = new MockMultipartFile("resume", "cv.pdf", "application/pdf", new byte[0]);
        var r = controller.review(file, VALID_JD, mockRequest);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    @Test void nullJd_returns400() {
        var r = controller.review(pdf("cv.pdf"), null, mockRequest);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertTrue(r.getBody().toString().contains("required"));
    }

    @Test void blankJd_returns400() {
        var r = controller.review(pdf("cv.pdf"), "   ", mockRequest);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    @Test void tooShortJd_returns400() {
        var r = controller.review(pdf("cv.pdf"), "Java dev", mockRequest);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertTrue(r.getBody().toString().contains("too short"));
    }

    @Test void unsupportedFileType_returns415() {
        when(pipeline.review(any(), any()))
            .thenThrow(new UnsupportedFileTypeException("Unsupported file type: cv.txt"));
        var r = controller.review(pdf("cv.txt"), VALID_JD, mockRequest);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, r.getStatusCode());
    }

    @Test void urlFetchFailed_returns400WithUserMessage() {
        when(pipeline.review(any(), any()))
            .thenThrow(new RuntimeException("Could not fetch job description from URL: 403"));
        // Use a URL that passes the length check (>50 chars)
        var r = controller.review(pdf("cv.pdf"), "https://jobs.example-company.com/careers/senior-backend-engineer-role-12345", mockRequest);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertNotNull(r.getBody());
        assertTrue(r.getBody().toString().contains("Paste the JD text directly")
            || r.getBody().toString().contains("paste"));
    }

    @Test void passwordProtectedPdf_returns400WithUserMessage() {
        when(pipeline.review(any(), any()))
            .thenThrow(new RuntimeException("Failed to review resume: password protected"));
        var r = controller.review(pdf("cv.pdf"), VALID_JD, mockRequest);
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertTrue(r.getBody().toString().contains("password-protected"));
    }

    @Test void unexpectedError_returns500WithGenericMessage() {
        when(pipeline.review(any(), any()))
            .thenThrow(new RuntimeException("NullPointerException in pipeline"));
        var r = controller.review(pdf("cv.pdf"), VALID_JD, mockRequest);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        // Must NOT expose internal stack trace to user
        assertFalse(r.getBody().toString().contains("NullPointerException"));
        assertTrue(r.getBody().toString().contains("try again"));
    }
}
