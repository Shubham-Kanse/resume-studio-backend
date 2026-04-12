package com.resumestudio.tracker;

import com.resumestudio.auth.SupabaseJwtVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/tracker/jobs")
public class JobTrackerController {

    private static final Logger log = LoggerFactory.getLogger(JobTrackerController.class);

    private static final Set<String> VALID_STAGES = Set.of(
        "To Apply", "Applied", "Interview", "Offer", "Ghosted", "Rejected"
    );
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );
    private static final long MAX_RESUME_BYTES = 10 * 1024 * 1024; // 10 MB

    private final JobApplicationRepository repo;
    private final ResumeStorageService storage;
    private final SupabaseJwtVerifier verifier;

    public JobTrackerController(JobApplicationRepository repo, ResumeStorageService storage, SupabaseJwtVerifier verifier) {
        this.repo = repo;
        this.storage = storage;
        this.verifier = verifier;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        var claims = verify(auth);
        if (claims == null) return unauthorized();
        return ResponseEntity.ok(repo.findByUserIdOrderByCreatedAtDesc(claims.userId()));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody Map<String, String> body) {
        var claims = verify(auth);
        if (claims == null) return unauthorized();
        if (body.containsKey("stage") && !VALID_STAGES.contains(body.get("stage"))) {
            return badRequest("Invalid stage value");
        }
        try {
            JobApplication job = new JobApplication();
            job.setUserId(claims.userId());
            applyFields(job, body);
            return ResponseEntity.ok(repo.save(job));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable String id,
                                    @RequestBody Map<String, String> body) {
        var claims = verify(auth);
        if (claims == null) return unauthorized();
        if (body.containsKey("stage") && !VALID_STAGES.contains(body.get("stage"))) {
            return badRequest("Invalid stage value");
        }
        var jobOpt = repo.findById(id).filter(j -> j.getUserId().equals(claims.userId()));
        if (jobOpt.isEmpty()) return ResponseEntity.notFound().build();
        try {
            JobApplication job = jobOpt.get();
            applyFields(job, body);
            return ResponseEntity.ok(repo.save(job));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable String id) {
        var claims = verify(auth);
        if (claims == null) return unauthorized();
        return repo.findById(id)
            .filter(j -> j.getUserId().equals(claims.userId()))
            .map(j -> {
                if (j.getResumeS3Key() != null) safeDelete(j.getResumeS3Key());
                repo.delete(j);
                return ResponseEntity.ok().build();
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<?> uploadResume(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable String id,
                                          @RequestParam("file") MultipartFile file) {
        var claims = verify(auth);
        if (claims == null) return unauthorized();

        if (file.isEmpty()) return badRequest("File is empty");
        if (file.getSize() > MAX_RESUME_BYTES) return badRequest("File exceeds 10 MB limit");
        String contentType = file.getContentType() != null ? file.getContentType() : "";
        if (!ALLOWED_MIME_TYPES.contains(contentType)) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Map.of("error", "Only PDF and Word documents are accepted"));
        }

        var jobOpt = repo.findById(id).filter(j -> j.getUserId().equals(claims.userId()));
        if (jobOpt.isEmpty()) return ResponseEntity.notFound().build();

        try {
            JobApplication j = jobOpt.get();
            if (j.getResumeS3Key() != null) safeDelete(j.getResumeS3Key());
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
            String key = storage.upload(claims.userId(), id, file, originalName);
            j.setResumeS3Key(key);
            j.setResumeName(originalName);
            repo.save(j);
            return ResponseEntity.ok(Map.of("resumeS3Key", key, "resumeName", originalName));
        } catch (RuntimeException e) {
            log.error("Resume upload failed for job {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Upload failed, please try again"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyFields(JobApplication job, Map<String, String> body) {
        if (body.containsKey("stage"))       job.setStage(body.get("stage"));
        if (body.containsKey("company"))     job.setCompany(body.get("company"));
        if (body.containsKey("position"))    job.setPosition(body.get("position"));
        if (body.containsKey("jobUrl"))      job.setJobUrl(body.get("jobUrl"));
        if (body.containsKey("notes"))       job.setNotes(body.get("notes"));
        if (body.containsKey("dateApplied") && body.get("dateApplied") != null && !body.get("dateApplied").isBlank()) {
            try {
                job.setDateApplied(LocalDate.parse(body.get("dateApplied")));
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid dateApplied format, expected YYYY-MM-DD");
            }
        }
    }

    private SupabaseJwtVerifier.UserClaims verify(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try { return verifier.verify(authHeader.substring(7)); }
        catch (Exception e) { return null; }
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }

    /** Best-effort S3 delete — logs on failure but never blocks the main operation. */
    private void safeDelete(String key) {
        try { storage.delete(key); }
        catch (Exception e) { log.warn("Failed to delete S3 object '{}': {}", key, e.getMessage()); }
    }
}
