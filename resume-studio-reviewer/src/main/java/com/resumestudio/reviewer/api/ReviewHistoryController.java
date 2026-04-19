package com.resumestudio.reviewer.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.auth.SupabaseJwtVerifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * GET /api/review/history — returns the user's past review jobs (metadata only, no full report).
 * Useful for showing a "Recent reviews" list in the UI.
 */
@RestController
@RequestMapping("/api/review")
public class ReviewHistoryController {

    private final ReviewJobRepository repo;
    private final SupabaseJwtVerifier verifier;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReviewHistoryController(ReviewJobRepository repo, SupabaseJwtVerifier verifier) {
        this.repo = repo;
        this.verifier = verifier;
    }

    @GetMapping("/history")
    public ResponseEntity<?> history(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @RequestParam(value = "offset", defaultValue = "0") int offset
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));

        SupabaseJwtVerifier.UserClaims claims;
        try { claims = verifier.verify(authHeader.substring(7)); }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token."));
        }

        int PAGE = 20;
        var pageResult = repo.findByUserIdPaged(claims.userId(), PageRequest.of(0, PAGE + 1 + Math.max(0, offset)));
        List<Map<String, Object>> items = pageResult.getContent()
            .stream()
            .skip(Math.max(0, offset))
            .limit(PAGE + 1)
            .map(j -> {
                java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("id", j.getId());
                m.put("status", j.getStatus());
                m.put("createdAt", j.getCreatedAt().toString());
                m.put("completedAt", j.getCompletedAt() != null ? j.getCompletedAt().toString() : "");
                m.put("outcome", j.getOutcome() != null ? j.getOutcome() : "");
                if ("DONE".equals(j.getStatus()) && j.getResultJson() != null) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode r = mapper.readTree(j.getResultJson());
                        m.put("verdict", r.path("verdict").asText(""));
                        m.put("score", r.path("score").path("composite").asInt(0));
                        m.put("roleTitle", r.path("roleContext").path("title").asText(""));
                        m.put("summaryLine", r.path("summaryLine").asText(""));
                    } catch (Exception ignored) {}
                }
                return (Map<String, Object>) m;
            })
            .toList();

        boolean hasMore = items.size() > PAGE;
        List<Map<String, Object>> page = hasMore ? items.subList(0, PAGE) : items;
        return ResponseEntity.ok(Map.of("items", page, "hasMore", hasMore));
    }

    /**
     * Retrieve a completed review result by job ID.
     * Allows users to bookmark/share their result links.
     */
    @GetMapping("/{jobId}/result")
    public ResponseEntity<?> result(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @org.springframework.web.bind.annotation.PathVariable String jobId
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        try { verifier.verify(authHeader.substring(7)); }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token."));
        }

        return repo.findById(jobId).map(j -> {
            if (!"DONE".equals(j.getStatus()) || j.getResultJson() == null)
                return ResponseEntity.status(HttpStatus.NOT_FOUND).<Object>body(Map.of("error", "Result not found."));
            try {
                return ResponseEntity.<Object>ok(mapper.readTree(j.getResultJson()));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).<Object>body(Map.of("error", "Could not read result."));
            }
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Job not found.")));
    }

    /**
     * Record interview outcome for a completed review.
     * Feeds the outcome tracking loop (Layer 10 from AI-integration.md).
     */
    @org.springframework.web.bind.annotation.PostMapping("/{jobId}/outcome")
    public ResponseEntity<?> outcome(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @org.springframework.web.bind.annotation.PathVariable String jobId,
        @org.springframework.web.bind.annotation.RequestBody Map<String, String> body
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));

        SupabaseJwtVerifier.UserClaims claims;
        try { claims = verifier.verify(authHeader.substring(7)); }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token."));
        }

        String outcome = body.get("outcome"); // "INTERVIEW" | "REJECTED" | "NO_RESPONSE"
        if (outcome == null) return ResponseEntity.badRequest().body(Map.of("error", "outcome is required."));

        // Verify the job belongs to this user, then record outcome
        repo.findById(jobId)
            .filter(j -> claims.userId().equals(j.getUserId()))
            .ifPresent(j -> {
                j.setOutcome(outcome);
                repo.save(j);
            });

        return ResponseEntity.ok(Map.of("recorded", true));
    }
}
