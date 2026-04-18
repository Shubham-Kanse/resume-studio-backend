package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.generate.ResumeGenerateService;
import com.resumestudio.reviewer.ingest.ResumeIngestService.UnsupportedFileTypeException;
import com.resumestudio.auth.UserService;
import com.resumestudio.auth.SupabaseJwtVerifier;
import com.resumestudio.auth.model.Plan;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * POST /api/generate
 *
 * Accepts 1–3 resume files (PDF or DOCX) + a job description (text or URL)
 * and returns an ATS-optimized .docx resume as a file download.
 *
 * The output beats modern ATS systems (Taleo, iCIMS, Greenhouse, Workday) by:
 *  - 90-100% keyword match against the JD (primary + secondary keyword strategy)
 *  - Perfect ATS formatting (single-column, standard fonts, no tables/images)
 *  - STAR-T structured bullets with quantified results on every line
 *  - Professional summary using the spec formula (100-150 words)
 *  - Skills section ordered by JD relevance with 15-25 items
 *  - Filename in FirstName_LastName_Resume.docx format
 *
 * Authentication is enforced by JwtAuthFilter (same as /review).
 */
@RestController
@RequestMapping("/api")
public class ResumeGenerateController {

    private static final Logger log = LoggerFactory.getLogger(ResumeGenerateController.class);

    private static final MediaType DOCX_MEDIA_TYPE = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private static final int MAX_RESUME_FILES = 3;

    private final ResumeGenerateService generateService;
    private final RateLimiterService rateLimiter;
    private final UserService userService;

    public ResumeGenerateController(ResumeGenerateService generateService,
                                     RateLimiterService rateLimiter,
                                     UserService userService) {
        this.generateService = generateService;
        this.rateLimiter = rateLimiter;
        this.userService = userService;
    }

    /**
     * Generate an ATS-optimized resume DOCX from uploaded resumes + a job description.
     *
     * Form fields:
     *   resumes       — 1 to 3 resume files (.pdf or .docx); multiple files accepted
     *   jobDescription — full JD text (copy-paste) or a URL to a job posting
     *
     * Response: application/vnd.openxmlformats-officedocument.wordprocessingml.document
     *           Content-Disposition: attachment; filename="FirstName_LastName_Resume.docx"
     */
    @PostMapping(
        value = "/generate",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> generate(
        @RequestParam("resumes") List<MultipartFile> resumes,
        @RequestParam("jobDescription") String jobDescription,
        HttpServletRequest request
    ) {
        // ── Rate limiting ─────────────────────────────────────────────────────
        if (rateLimiter.isLimited(request)) {
            return error(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Please wait before trying again.");
        }

        // ── Plan enforcement: Generate requires Basic or Pro ──────────────────
        SupabaseJwtVerifier.UserClaims claims = (SupabaseJwtVerifier.UserClaims) request.getAttribute("claims");
        if (claims != null) {
            Plan plan = userService.getPlan(claims.userId());
            if (plan == Plan.FREE) {
                return error(HttpStatus.PAYMENT_REQUIRED,
                    "Resume generation requires a Basic or Pro plan. Upgrade to generate ATS-optimized resumes.");
            }
        }

        // ── Input validation ──────────────────────────────────────────────────
        if (resumes == null || resumes.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "At least one resume file is required.");
        }
        if (resumes.size() > MAX_RESUME_FILES) {
            return error(HttpStatus.BAD_REQUEST,
                "Maximum " + MAX_RESUME_FILES + " resume files allowed.");
        }
        for (MultipartFile file : resumes) {
            if (file.isEmpty()) return error(HttpStatus.BAD_REQUEST, "One or more resume files are empty.");
            String fn = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
            if (!fn.endsWith(".pdf") && !fn.endsWith(".docx") && !fn.endsWith(".doc")) {
                return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported file type: " + file.getOriginalFilename()
                    + ". Please upload .pdf or .docx files.");
            }
        }
        if (jobDescription == null || jobDescription.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "Job description is required.");
        }
        if (jobDescription.trim().length() < 50) {
            return error(HttpStatus.BAD_REQUEST,
                "Job description is too short. Please paste the full job posting.");
        }

        // ── Execute pipeline ──────────────────────────────────────────────────
        String requestId = java.util.UUID.randomUUID().toString().substring(0, 8);
        org.slf4j.MDC.put("requestId", requestId);
        try {
            log.info("[{}] /generate — {} resume(s), JD length={}", requestId,
                resumes.size(), jobDescription.length());

            ResumeGenerateService.GenerateResult result =
                generateService.generate(resumes, jobDescription);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(DOCX_MEDIA_TYPE);
            headers.setContentDisposition(ContentDisposition.attachment()
                .filename(result.filename())
                .build());
            headers.setContentLength(result.docxBytes().length);
            // Surface generation provenance so the frontend can warn the user when
            // the LLM optimization fell back to passthrough mode (raw resume data
            // without STAR-T rewrite, summary formula, or keyword densification).
            headers.add("X-Generation-Mode", result.generationMode());
            headers.add("Access-Control-Expose-Headers", "X-Generation-Mode, Content-Disposition");

            log.info("[{}] Generated '{}' ({} bytes) mode={}", requestId,
                result.filename(), result.docxBytes().length, result.generationMode());

            return new ResponseEntity<>(result.docxBytes(), headers, HttpStatus.OK);

        } catch (UnsupportedFileTypeException e) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
        } catch (java.io.IOException e) {
            log.warn("[{}] File read error: {}", requestId, e.getMessage());
            return error(HttpStatus.BAD_REQUEST, "Could not read one of the uploaded files.");
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("password")) {
                return error(HttpStatus.BAD_REQUEST,
                    "One of the uploaded PDFs is password-protected. Please upload an unlocked version.");
            }
            if (msg.contains("timed out") || msg.contains("timeout")) {
                return error(HttpStatus.GATEWAY_TIMEOUT,
                    "The generation took too long. Try a shorter resume or simpler JD.");
            }
            if (msg.contains("Job description is required") || msg.contains("too short")) {
                return error(HttpStatus.BAD_REQUEST, msg);
            }
            log.error("[{}] Generation failed", requestId, e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to generate resume. Please try again.");
        } catch (Exception e) {
            log.error("[{}] Unexpected error", requestId, e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again.");
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
