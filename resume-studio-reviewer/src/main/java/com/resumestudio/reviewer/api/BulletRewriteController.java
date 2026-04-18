package com.resumestudio.reviewer.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.auth.SupabaseJwtVerifier;
import com.resumestudio.auth.UserService;
import com.resumestudio.auth.model.Plan;
import com.resumestudio.reviewer.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * POST /api/bullets/rewrite
 *
 * Takes a weak bullet and JD context → returns 3 STAR-T rewrite variants.
 * Pro plan only.
 *
 * Request body:
 *   { "bullet": "...", "jobTitle": "...", "requiredSkills": [...] }
 *
 * Response:
 *   { "rewrites": ["...", "...", "..."] }
 */
@RestController
@RequestMapping("/api/bullets")
public class BulletRewriteController {

    private static final Logger log = LoggerFactory.getLogger(BulletRewriteController.class);

    private final SupabaseJwtVerifier verifier;
    private final UserService userService;
    private final AiProperties ai;
    private final RateLimiterService rateLimiter;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    public BulletRewriteController(SupabaseJwtVerifier verifier, UserService userService,
                                    AiProperties ai, RateLimiterService rateLimiter) {
        this.verifier = verifier;
        this.userService = userService;
        this.ai = ai;
        this.rateLimiter = rateLimiter;
    }

    public record RewriteRequest(String bullet, String jobTitle, List<String> requiredSkills) {}

    @PostMapping("/rewrite")
    public ResponseEntity<?> rewrite(
        @RequestHeader(value = "Authorization", required = false) String authHeader,
        @RequestBody RewriteRequest body,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Authentication required."));

        SupabaseJwtVerifier.UserClaims claims;
        try { claims = verifier.verify(authHeader.substring(7)); }
        catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token."));
        }

        // Pro plan only
        if (userService.getPlan(claims.userId()) != Plan.PRO) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .body(Map.of("error", "Bullet rewriting requires a Pro plan."));
        }

        if (rateLimiter.isLimited(request))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Too many requests."));

        if (body.bullet() == null || body.bullet().isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Bullet text is required."));
        if (body.bullet().length() > 500)
            return ResponseEntity.badRequest().body(Map.of("error", "Bullet too long (max 500 chars)."));

        try {
            List<String> rewrites = callGroqForRewrites(body);
            return ResponseEntity.ok(Map.of("rewrites", rewrites));
        } catch (Exception e) {
            log.error("Bullet rewrite failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Rewrite failed. Please try again."));
        }
    }

    private List<String> callGroqForRewrites(RewriteRequest req) throws Exception {
        String skills = req.requiredSkills() != null
            ? String.join(", ", req.requiredSkills().stream().limit(8).toList())
            : "";

        String prompt = """
            Rewrite the following resume bullet into 3 improved STAR-T variants.
            Rules for each variant:
            - Start with a strong action verb (Reduced, Built, Led, Delivered, etc.)
            - Include a quantified result if inferable (%, time saved, scale)
            - Reference the tech/tools from the required skills where naturally applicable
            - Max 25 words per bullet
            - Do NOT invent metrics that aren't implied — use qualitative impact if needed
            - Output ONLY valid JSON: {"rewrites": ["...", "...", "..."]}

            Job title: %s
            Required skills: %s
            Original bullet: %s
            """.formatted(
                req.jobTitle() != null ? req.jobTitle() : "Software Engineer",
                skills,
                req.bullet().trim());

        String reqBody = mapper.writeValueAsString(Map.of(
            "model", ai.getModel(),
            "messages", List.of(
                Map.of("role", "system", "content",
                    "You are a professional resume writer. Output ONLY valid JSON. Never invent metrics not present in the original bullet."),
                Map.of("role", "user", "content", prompt)
            ),
            "temperature", 0.3,
            "max_tokens", 300
        ));

        HttpRequest httpReq = HttpRequest.newBuilder()
            .uri(URI.create(ai.getUrl()))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + ai.getKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(reqBody))
            .build();

        HttpResponse<String> resp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("Groq error: " + resp.statusCode());

        JsonNode root = mapper.readTree(resp.body());
        String content = root.path("choices").get(0).path("message").path("content").asText();
        content = content.replaceAll("```json\\s*|```", "").trim();
        int start = content.indexOf('{'), end = content.lastIndexOf('}');
        if (start >= 0 && end > start) content = content.substring(start, end + 1);

        JsonNode parsed = mapper.readTree(content);
        JsonNode rewrites = parsed.path("rewrites");
        if (!rewrites.isArray() || rewrites.size() == 0)
            throw new RuntimeException("No rewrites in response");

        List<String> result = new java.util.ArrayList<>();
        rewrites.forEach(n -> result.add(n.asText()));
        return result.stream().limit(3).toList();
    }
}
