package com.resumestudio.tracker;

import com.resumestudio.auth.SupabaseJwtVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
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
    private static final long MAX_RESUME_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final int MIN_REMINDER_FREQUENCY_DAYS = 1;
    private static final int MAX_REMINDER_FREQUENCY_DAYS = 14;
    private static final Set<String> TERMINAL_STAGES = Set.of("Offer", "Rejected", "Ghosted");

    private final JobApplicationRepository repo;
    private final ResumeStorageService storage;
    private final SupabaseJwtVerifier verifier;
    private final com.resumestudio.auth.UserService userService;

    public JobTrackerController(JobApplicationRepository repo, ResumeStorageService storage,
                                 SupabaseJwtVerifier verifier, com.resumestudio.auth.UserService userService) {
        this.repo = repo;
        this.storage = storage;
        this.verifier = verifier;
        this.userService = userService;
    }

    private com.resumestudio.auth.model.Plan userPlan(String userId) {
        return userService.getPlan(userId);
    }

    private com.resumestudio.auth.model.User userProfile(String userId) {
        return userService.getOrCreate(userId);
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        var claims = verify(auth);
        if (claims == null) return unauthorized();
        return ResponseEntity.ok(repo.findByUserIdOrderByCreatedAtDesc(claims.userId()));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody Map<String, ?> body) {
        var claims = verify(auth);
        if (claims == null) return unauthorized();
        if (hasInvalidStage(body)) return badRequest("Invalid stage value");

        // Free plan: max 10 tracker jobs
        com.resumestudio.auth.model.Plan plan = verifier != null ? userPlan(claims.userId()) : com.resumestudio.auth.model.Plan.FREE;
        if (plan == com.resumestudio.auth.model.Plan.FREE) {
            long count = repo.countByUserId(claims.userId());
            if (count >= 10) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.PAYMENT_REQUIRED)
                    .body(Map.of("error", "Free plan is limited to 10 tracked jobs. Upgrade to Basic or Pro for unlimited tracking."));
            }
        }

        try {
            com.resumestudio.auth.model.User profile = userProfile(claims.userId());
            JobApplication job = new JobApplication();
            job.setUserId(claims.userId());
            if (claims.email() != null && !claims.email().isBlank()) {
                job.setUserEmail(claims.email());
            }
            job.setReminderEnabled(profile.isReminderEmailsEnabled());
            job.setReminderFrequencyDays(profile.getReminderFrequencyDays());
            applyFields(job, body);
            return ResponseEntity.ok(repo.save(job));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable String id,
                                    @RequestBody Map<String, ?> body) {
        var claims = verify(auth);
        if (claims == null) return unauthorized();
        if (hasInvalidStage(body)) return badRequest("Invalid stage value");
        var jobOpt = repo.findById(id).filter(j -> j.getUserId().equals(claims.userId()));
        if (jobOpt.isEmpty()) return ResponseEntity.notFound().build();
        try {
            JobApplication job = jobOpt.get();
            if ((job.getUserEmail() == null || job.getUserEmail().isBlank())
                && claims.email() != null && !claims.email().isBlank()) {
                job.setUserEmail(claims.email());
            }
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
        if (file.getSize() > MAX_RESUME_BYTES) return badRequest("File exceeds 5 MB limit");
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
            String safeName = originalName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
            String key = storage.upload(claims.userId(), id, file, safeName);
            j.setResumeS3Key(key);
            j.setResumeName(safeName);
            repo.save(j);
            return ResponseEntity.ok(Map.of("resumeS3Key", key, "resumeName", originalName));
        } catch (RuntimeException e) {
            log.error("Resume upload failed for job {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Upload failed, please try again"));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyFields(JobApplication job, Map<String, ?> body) {
        if (body.containsKey("stage")) {
            String stage = asString(body.get("stage"));
            if (stage == null || !VALID_STAGES.contains(stage)) throw new IllegalArgumentException("Invalid stage value");
            job.setStage(stage);
        }
        if (body.containsKey("company"))     job.setCompany(asString(body.get("company")));
        if (body.containsKey("position"))    job.setPosition(asString(body.get("position")));
        if (body.containsKey("jobUrl"))      job.setJobUrl(asString(body.get("jobUrl")));
        if (body.containsKey("notes"))       job.setNotes(asString(body.get("notes")));
        if (body.containsKey("dateApplied")) {
            String dateApplied = asString(body.get("dateApplied"));
            if (dateApplied == null || dateApplied.isBlank()) {
                job.setDateApplied(null);
            } else {
                try {
                    job.setDateApplied(LocalDate.parse(dateApplied));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid dateApplied format, expected YYYY-MM-DD");
                }
            }
        }
        if (body.containsKey("reminderEnabled")) {
            job.setReminderEnabled(asBoolean(body.get("reminderEnabled"), "reminderEnabled"));
        }
        if (body.containsKey("reminderFrequencyDays")) {
            int days = asInt(body.get("reminderFrequencyDays"), "reminderFrequencyDays");
            if (days < MIN_REMINDER_FREQUENCY_DAYS || days > MAX_REMINDER_FREQUENCY_DAYS) {
                throw new IllegalArgumentException("reminderFrequencyDays must be between 1 and 14");
            }
            job.setReminderFrequencyDays(days);
        }
        if (body.containsKey("nextReminderAt")) {
            String nextReminderAt = asString(body.get("nextReminderAt"));
            if (nextReminderAt == null || nextReminderAt.isBlank()) {
                job.setNextReminderAt(null);
            } else {
                try {
                    job.setNextReminderAt(Instant.parse(nextReminderAt));
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid nextReminderAt format, expected ISO-8601 instant");
                }
            }
        }

        if (TERMINAL_STAGES.contains(job.getStage())) {
            job.setReminderEnabled(false);
            job.setNextReminderAt(null);
            return;
        }

        if (job.isReminderEnabled() && job.getNextReminderAt() == null) {
            job.setNextReminderAt(Instant.now().plusSeconds((long) job.getReminderFrequencyDays() * 86400));
        }
        if (!job.isReminderEnabled()) {
            job.setNextReminderAt(null);
        }
    }

    private String asString(Object value) {
        if (value == null) return null;
        return String.valueOf(value);
    }

    private boolean asBoolean(Object value, String fieldName) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
        }
        throw new IllegalArgumentException("Invalid boolean value for " + fieldName);
    }

    private int asInt(Object value, String fieldName) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new IllegalArgumentException("Invalid number value for " + fieldName);
    }

    private boolean hasInvalidStage(Map<String, ?> body) {
        if (!body.containsKey("stage")) return false;
        String stage = asString(body.get("stage"));
        return stage == null || !VALID_STAGES.contains(stage);
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
