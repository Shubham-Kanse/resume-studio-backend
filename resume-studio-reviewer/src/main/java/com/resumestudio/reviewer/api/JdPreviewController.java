package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.extraction.JdParserService;
import com.resumestudio.reviewer.model.JobDescription;
import com.resumestudio.reviewer.model.enums.JdClarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Pre-flight JD analysis. Lets the UI show the user *what we extracted* from
 * their JD before they commit to a full review — the trust gate.
 *
 * Accepts either raw text or a URL (resolved via {@link JdFetchService}).
 * Returns the structured fields a solo job-seeker needs to verify:
 *  - role title we detected
 *  - must-have / nice-to-have / inferred skills
 *  - YOE range
 *  - JD clarity score and any parser warnings
 *
 * If something is off (wrong title, missing skills), the user catches it here
 * instead of getting a confusing verdict downstream.
 */
@RestController
@RequestMapping("/api/jd")
public class JdPreviewController {

    private static final Logger log = LoggerFactory.getLogger(JdPreviewController.class);
    private static final int MAX_JD_CHARS = 50_000;

    private final JdParserService parser;
    private final JdFetchService fetcher;
    private final RateLimiterService rateLimiter;

    public JdPreviewController(JdParserService parser, JdFetchService fetcher, RateLimiterService rateLimiter) {
        this.parser = parser;
        this.fetcher = fetcher;
        this.rateLimiter = rateLimiter;
    }

    public record PreviewRequest(String input) {}

    public record PreviewResponse(
        String title,
        String domain,
        List<String> mustHaveSkills,
        List<String> niceToHaveSkills,
        List<String> inferredSkills,
        Double yoeMin,
        Double yoeMax,
        String seniorityHint,
        JdClarity jdClarity,
        List<String> warnings,
        boolean resolvedFromUrl,
        int jdTextChars,
        String resolvedText   // the fetched/cleaned JD text — use this for scoreText to avoid re-fetching
    ) {}

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody PreviewRequest body,
                                      jakarta.servlet.http.HttpServletRequest request) {
        if (rateLimiter.isPreviewLimited(request)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Too many requests. Please wait a moment."));
        }
        if (body == null || body.input() == null || body.input().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "JD input is required."));
        }

        String trimmed = body.input().trim();
        boolean isUrl = trimmed.matches("(?i)^https?://\\S+$");
        String resolved;
        try {
            resolved = fetcher.resolve(trimmed);
        } catch (RuntimeException e) {
            log.info("JD URL fetch failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", e.getMessage()));
        }

        if (resolved == null || resolved.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "JD content is empty after fetching."));
        }
        if (resolved.length() > MAX_JD_CHARS) {
            resolved = resolved.substring(0, MAX_JD_CHARS);
        }

        JobDescription parsed;
        try {
            parsed = parser.parse(resolved);
        } catch (RuntimeException e) {
            log.warn("JD parse failed", e);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "Could not parse the job description."));
        }

        String seniorityHint = seniorityHintFromIc(parsed.getIcLevel());
        PreviewResponse response = new PreviewResponse(
            parsed.getRoleTitle(),
            parsed.getCompanyCulture(),
            safe(parsed.getMustHaveSkills()),
            safe(parsed.getNiceToHaveSkills()),
            safe(parsed.getImpliedSkills()),
            parsed.getYoeMin(),
            parsed.getYoeMax(),
            seniorityHint,
            parsed.getJdClarity() != null ? parsed.getJdClarity() : JdClarity.MEDIUM,
            safe(parsed.getParseWarnings()),
            isUrl,
            resolved.length(),
            isUrl ? resolved : null  // only send back resolved text when input was a URL
        );
        return ResponseEntity.ok(response);
    }

    private static List<String> safe(List<String> in) {
        return in == null ? List.of() : in;
    }

    private static String seniorityHintFromIc(int ic) {
        return switch (ic) {
            case 1 -> "Junior / IC1";
            case 2 -> "Mid / IC2";
            case 3 -> "Senior / IC3";
            case 4 -> "Staff / IC4";
            case 5 -> "Senior Staff / IC5";
            case 6 -> "Principal / IC6";
            default -> null;
        };
    }
}
