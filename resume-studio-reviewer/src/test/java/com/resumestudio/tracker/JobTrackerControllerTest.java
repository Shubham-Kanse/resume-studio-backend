package com.resumestudio.tracker;

import com.resumestudio.auth.SupabaseJwtVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobTrackerControllerTest {

    private JobApplicationRepository repo;
    private ResumeStorageService storage;
    private SupabaseJwtVerifier verifier;
    private JobTrackerController controller;

    private static final String BEARER = "Bearer valid-token";
    private static final SupabaseJwtVerifier.UserClaims CLAIMS =
        new SupabaseJwtVerifier.UserClaims("user-1", "user@example.com");

    @BeforeEach
    void setUp() {
        repo = mock(JobApplicationRepository.class);
        storage = mock(ResumeStorageService.class);
        verifier = mock(SupabaseJwtVerifier.class);
        controller = new JobTrackerController(repo, storage, verifier);
        when(verifier.verify("valid-token")).thenReturn(CLAIMS);
    }

    // ── Auth guard ────────────────────────────────────────────────────────────

    @Test void noAuth_list_returns401() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.list(null).getStatusCode());
    }

    @Test void badToken_list_returns401() {
        when(verifier.verify("bad")).thenThrow(new IllegalArgumentException("bad"));
        assertEquals(HttpStatus.UNAUTHORIZED, controller.list("Bearer bad").getStatusCode());
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Test void list_returnsUserJobs() {
        var job = job("user-1");
        when(repo.findByUserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of(job));
        var r = controller.list(BEARER);
        assertEquals(HttpStatus.OK, r.getStatusCode());
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Test void create_savesAndReturnsJob() {
        var job = job("user-1");
        when(repo.save(any())).thenReturn(job);
        var r = controller.create(BEARER, Map.of("stage", "To Apply"));
        assertEquals(HttpStatus.OK, r.getStatusCode());
    }

    @Test void create_invalidStage_returns400() {
        var r = controller.create(BEARER, Map.of("stage", "HACKED"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Test void update_ownJob_succeeds() {
        var job = job("user-1");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        when(repo.save(any())).thenReturn(job);
        var r = controller.update(BEARER, "job-1", Map.of("company", "Acme"));
        assertEquals(HttpStatus.OK, r.getStatusCode());
    }

    @Test void update_otherUsersJob_returns404() {
        var job = job("other-user");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        var r = controller.update(BEARER, "job-1", Map.of("company", "Acme"));
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    @Test void update_nonExistentJob_returns404() {
        when(repo.findById("missing")).thenReturn(Optional.empty());
        var r = controller.update(BEARER, "missing", Map.of("company", "Acme"));
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
    }

    @Test void update_invalidStage_returns400() {
        var r = controller.update(BEARER, "job-1", Map.of("stage", "INVALID"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    @Test void update_invalidDateFormat_returns400() {
        var job = job("user-1");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        var r = controller.update(BEARER, "job-1", Map.of("dateApplied", "not-a-date"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test void delete_ownJob_deletesAndReturns200() {
        var job = job("user-1");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        var r = controller.delete(BEARER, "job-1");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        verify(repo).delete(job);
    }

    @Test void delete_jobWithResume_deletesS3ThenDb() {
        var job = job("user-1");
        job.setResumeS3Key("user-1/job-1/cv.pdf");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        controller.delete(BEARER, "job-1");
        verify(storage).delete("user-1/job-1/cv.pdf");
        verify(repo).delete(job);
    }

    @Test void delete_s3Fails_stillDeletesDbRecord() {
        var job = job("user-1");
        job.setResumeS3Key("user-1/job-1/cv.pdf");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        doThrow(new RuntimeException("S3 down")).when(storage).delete(any());
        // Should not throw
        var r = controller.delete(BEARER, "job-1");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        verify(repo).delete(job); // DB delete still happens
    }

    @Test void delete_otherUsersJob_returns404() {
        when(repo.findById("job-1")).thenReturn(Optional.of(job("other-user")));
        assertEquals(HttpStatus.NOT_FOUND, controller.delete(BEARER, "job-1").getStatusCode());
    }

    // ── Upload resume ─────────────────────────────────────────────────────────

    @Test void uploadResume_validPdf_storesAndReturnsKeys() {
        var job = job("user-1");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        when(storage.upload(eq("user-1"), eq("job-1"), any(), eq("cv.pdf"))).thenReturn("user-1/job-1/cv.pdf");
        when(repo.save(any())).thenReturn(job);

        var file = new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[100]);
        var r = controller.uploadResume(BEARER, "job-1", file);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        var body = (Map<?, ?>) r.getBody();
        assertEquals("user-1/job-1/cv.pdf", body.get("resumeS3Key"));
        assertEquals("cv.pdf", body.get("resumeName"));
    }

    @Test void uploadResume_emptyFile_returns400() {
        var file = new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[0]);
        assertEquals(HttpStatus.BAD_REQUEST, controller.uploadResume(BEARER, "job-1", file).getStatusCode());
    }

    @Test void uploadResume_tooLarge_returns400() {
        var file = new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[11 * 1024 * 1024]);
        assertEquals(HttpStatus.BAD_REQUEST, controller.uploadResume(BEARER, "job-1", file).getStatusCode());
    }

    @Test void uploadResume_wrongMimeType_returns415() {
        var file = new MockMultipartFile("file", "cv.exe", "application/octet-stream", new byte[100]);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, controller.uploadResume(BEARER, "job-1", file).getStatusCode());
    }

    @Test void uploadResume_otherUsersJob_returns404() {
        when(repo.findById("job-1")).thenReturn(Optional.of(job("other-user")));
        var file = new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[100]);
        assertEquals(HttpStatus.NOT_FOUND, controller.uploadResume(BEARER, "job-1", file).getStatusCode());
    }

    @Test void uploadResume_replacesExistingResume_deletesOldKey() {
        var job = job("user-1");
        job.setResumeS3Key("user-1/job-1/old.pdf");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        when(storage.upload(any(), any(), any(), any())).thenReturn("user-1/job-1/new.pdf");
        when(repo.save(any())).thenReturn(job);

        var file = new MockMultipartFile("file", "new.pdf", "application/pdf", new byte[100]);
        controller.uploadResume(BEARER, "job-1", file);
        verify(storage).delete("user-1/job-1/old.pdf");
    }

    @Test void uploadResume_s3Fails_returns500WithMessage() {
        var job = job("user-1");
        when(repo.findById("job-1")).thenReturn(Optional.of(job));
        when(storage.upload(any(), any(), any(), any())).thenThrow(new RuntimeException("S3 unavailable"));

        var file = new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[100]);
        var r = controller.uploadResume(BEARER, "job-1", file);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, r.getStatusCode());
        assertTrue(r.getBody().toString().contains("Upload failed"));
        // DB record must NOT be modified
        verify(repo, never()).save(any());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private JobApplication job(String userId) {
        var j = new JobApplication();
        j.setUserId(userId);
        return j;
    }
}
