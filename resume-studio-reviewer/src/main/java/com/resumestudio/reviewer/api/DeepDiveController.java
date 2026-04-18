package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.ReviewCache;
import com.resumestudio.reviewer.ReviewerPipeline;
import com.resumestudio.reviewer.ingest.ResumeIngestService;
import com.resumestudio.reviewer.model.DeepDiveReport;
import com.resumestudio.auth.UserService;
import com.resumestudio.auth.SupabaseJwtVerifier;
import com.resumestudio.auth.model.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class DeepDiveController {

    private static final Logger log = LoggerFactory.getLogger(DeepDiveController.class);

    private final ReviewerPipeline pipeline;
    private final ReviewCache reviewCache;
    private final RateLimiterService rateLimiter;
    private final UserService userService;

    public DeepDiveController(ReviewerPipeline pipeline, ReviewCache reviewCache,
                               RateLimiterService rateLimiter, UserService userService) {
        this.pipeline = pipeline;
        this.reviewCache = reviewCache;
        this.rateLimiter = rateLimiter;
        this.userService = userService;
    }

    @PostMapping("/deepDive")
    public ResponseEntity<?> deepDive(
        @RequestParam("resume") MultipartFile resume,
        @RequestParam("jobDescription") String jobDescription,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        if (rateLimiter.isLimited(request))
            return error(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please wait a minute before trying again.");

        // Plan enforcement: deep dive requires Basic or Pro
        SupabaseJwtVerifier.UserClaims claims = (SupabaseJwtVerifier.UserClaims) request.getAttribute("claims");
        if (claims != null) {
            Plan plan = userService.getPlan(claims.userId());
            if (plan == Plan.FREE) {
                return error(HttpStatus.PAYMENT_REQUIRED,
                    "Deep Dive requires a Basic or Pro plan. Upgrade to unlock section-by-section analysis.");
            }
        }
        if (resume == null || resume.isEmpty())
            return error(HttpStatus.BAD_REQUEST, "No resume file provided.");
        if (jobDescription == null || jobDescription.isBlank())
            return error(HttpStatus.BAD_REQUEST, "Job description is required.");
        if (jobDescription.trim().length() < 50)
            return error(HttpStatus.BAD_REQUEST, "Job description is too short. Paste the full JD or provide a URL.");

        String lowerFilename = resume.getOriginalFilename() != null ? resume.getOriginalFilename().toLowerCase() : "";
        if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg") || lowerFilename.endsWith(".png"))
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Please upload your resume as a PDF or Word document (.docx).");

        try {
            byte[] resumeBytes;
            try { resumeBytes = resume.getBytes(); } catch (java.io.IOException e) {
                return error(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
            }

            DeepDiveReport cached = reviewCache.getDeepDive(resumeBytes, jobDescription);
            if (cached != null) {
                log.info("DeepDive cache hit");
                return ResponseEntity.ok(cached);
            }

            // Reuse signals from a prior review if available — avoids full re-ingestion
            com.resumestudio.reviewer.model.ResumeSignals cachedSignals = reviewCache.getSignals(resumeBytes, jobDescription);
            com.resumestudio.reviewer.model.Resume cachedResume = reviewCache.getResume(resumeBytes, jobDescription);

            DeepDiveReport report = (cachedSignals != null && cachedResume != null)
                ? pipeline.deepDiveWithSignals(resume, jobDescription, cachedSignals, cachedResume)
                : pipeline.deepDive(resume, jobDescription);
            reviewCache.putDeepDive(resumeBytes, jobDescription, report);
            return ResponseEntity.ok(report);
        } catch (ResumeIngestService.UnsupportedFileTypeException e) {
            return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
        } catch (java.io.IOException e) {
            return error(HttpStatus.BAD_REQUEST, "Could not read the uploaded file.");
        } catch (RuntimeException e) {
            log.error("Deep dive failed", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");
        }
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
