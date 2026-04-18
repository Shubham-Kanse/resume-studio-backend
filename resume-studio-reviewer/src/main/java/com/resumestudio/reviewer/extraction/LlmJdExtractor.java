package com.resumestudio.reviewer.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumestudio.reviewer.AiProperties;
import com.resumestudio.reviewer.model.JobDescription;
import com.resumestudio.reviewer.model.enums.JdClarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts structured fields from a raw JD using the LLM.
 *
 * Why LLM instead of regex:
 *   - JDs come from hundreds of ATS platforms with different formatting
 *   - Section headers vary wildly: "Required Qualifications", "What You'll Need",
 *     "Minimum Qualifications", "Must Have", "The Ideal Candidate", etc.
 *   - "or" logic, conditional requirements, and implicit requirements need
 *     semantic understanding, not pattern matching
 *   - The LLM already understands what "proficiency in" vs "familiarity with" means
 *
 * The regex parser in JdParserService remains as a fast fallback.
 */
@Service
public class LlmJdExtractor {

    private static final Logger log = LoggerFactory.getLogger(LlmJdExtractor.class);

    private final AiProperties ai;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    public LlmJdExtractor(AiProperties ai) {
        this.ai = ai;
    }

    private static final String PROMPT = """
        Extract from job description. JSON only, no explanation.
        roleTitle: full job title including seniority (e.g. "Senior Software Engineer")
        requiredSkills: atomic skill/tool/language names from Required/Must-have/Minimum sections only. No phrases, no soft skills. For "X or Y" groups include all options (candidate needs at least one). E.g. ["JavaScript","TypeScript","React","Node.js","MySQL","PostgreSQL","Docker","Kubernetes","Git","AWS"]
        preferredSkills: atomic skill names from Preferred/Nice-to-have/Bonus sections only
        yoeMin: min years from REQUIRED section, null if absent
        yoeMax: max years, null if open-ended
        icLevel: 1=intern/junior 2=associate 3=mid 4=senior 5=staff/principal 6=director+
        {"roleTitle":"","requiredSkills":[],"preferredSkills":[],"yoeMin":null,"yoeMax":null,"icLevel":3}
        JD:
        """;

    /**
     * Calls the LLM to extract structured JD fields.
     * Returns null if the call fails — caller falls back to regex parser.
     */
    public LlmJdResult extract(String jdText) {
        String prepared = prepareForExtraction(jdText);

        try {
            String body = mapper.writeValueAsString(new GroqRequest(ai.getModel(), PROMPT + prepared));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ai.getUrl()))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + ai.getKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("LLM JD extraction failed: HTTP {}", resp.statusCode());
                return null;
            }

            JsonNode root = mapper.readTree(resp.body());
            String content = root.path("choices").get(0).path("message").path("content").asText();
            content = content.replaceAll("(?s)```json\\s*", "").replaceAll("```", "").trim();
            int start = content.indexOf('{'), end = content.lastIndexOf('}');
            if (start < 0 || end <= start) return null;

            JsonNode parsed = mapper.readTree(content.substring(start, end + 1));
            return fromJson(parsed);

        } catch (Exception e) {
            log.warn("LLM JD extraction error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Strips boilerplate that adds tokens without adding signal:
     * - Company "About us" paragraphs (appear before the role description)
     * - Benefits / perks sections
     * - EEO / legal statements
     * - Salary disclosure boilerplate
     *
     * Keeps everything that looks like requirements, responsibilities, or qualifications.
     * If still over 6000 chars, truncates from the END (boilerplate is always at the end).
     */
    private static final java.util.regex.Pattern BOILERPLATE_SECTION = java.util.regex.Pattern.compile(
        "(?im)^(our benefits|benefits|perks|what we offer|compensation|equal opportunity|" +
        "eeo|diversity|inclusion|our culture|" +
        "travel percent|subsidiary|additional location|pay range|base pay|salary).*",
        java.util.regex.Pattern.DOTALL
    );

    private String prepareForExtraction(String jdText) {
        // Strip trailing boilerplate sections
        String stripped = BOILERPLATE_SECTION.matcher(jdText).replaceFirst("").trim();

        // If still long, keep up to 6000 chars (truncate from end — requirements are in the middle)
        if (stripped.length() > 6000) {
            stripped = stripped.substring(0, 6000);
            // Trim to last complete line
            int lastNewline = stripped.lastIndexOf('\n');
            if (lastNewline > 4000) stripped = stripped.substring(0, lastNewline);
        }

        return stripped;
    }

    private LlmJdResult fromJson(JsonNode node) {
        String title = node.path("roleTitle").asText(null);
        List<String> required = filterSkillNoise(toList(node.path("requiredSkills")));
        List<String> preferred = filterSkillNoise(toList(node.path("preferredSkills")));
        Double yoeMin = node.path("yoeMin").isNull() ? null : node.path("yoeMin").asDouble();
        Double yoeMax = node.path("yoeMax").isNull() ? null : node.path("yoeMax").asDouble();
        int icLevel = node.path("icLevel").asInt(3);
        return new LlmJdResult(title, required, preferred, yoeMin, yoeMax, icLevel);
    }

    // Reject skills that are clearly noise: too long, contain verbs, or are generic phrases
    private static final java.util.Set<String> SKILL_NOISE_WORDS = java.util.Set.of(
        "experience", "knowledge", "understanding", "ability", "skills", "proficiency",
        "familiarity", "background", "exposure", "working", "strong", "good", "excellent",
        "proven", "demonstrated", "solid", "hands-on", "hands on", "click", "request",
        "requests", "workplace", "drive", "google drive", "meta", "workplace from meta"
    );

    private List<String> filterSkillNoise(List<String> skills) {
        return skills.stream()
            .filter(s -> s != null && !s.isBlank())
            .filter(s -> s.length() <= 40)                          // reject overly long phrases
            .filter(s -> s.split("\\s+").length <= 4)               // reject 5+ word phrases
            .filter(s -> !SKILL_NOISE_WORDS.contains(s.toLowerCase().trim()))
            .filter(s -> !s.toLowerCase().contains("experience with"))
            .filter(s -> !s.toLowerCase().contains("knowledge of"))
            .collect(java.util.stream.Collectors.toList());
    }

    private List<String> toList(JsonNode arr) {
        List<String> list = new ArrayList<>();
        if (arr.isArray()) arr.forEach(n -> { if (!n.asText("").isBlank()) list.add(n.asText()); });
        return list;
    }

    public record LlmJdResult(
        String roleTitle,
        List<String> requiredSkills,
        List<String> preferredSkills,
        Double yoeMin,
        Double yoeMax,
        int icLevel
    ) {}

    private static class GroqRequest {
        @com.fasterxml.jackson.annotation.JsonProperty("model") public final String model;
        @com.fasterxml.jackson.annotation.JsonProperty("messages") public final List<java.util.Map<String, String>> messages;
        @com.fasterxml.jackson.annotation.JsonProperty("temperature") public final double temperature = 0.0;
        @com.fasterxml.jackson.annotation.JsonProperty("max_tokens") public final int maxTokens = 400;

        GroqRequest(String model, String prompt) {
            this.model = model;
            this.messages = List.of(
                java.util.Map.of("role", "system", "content",
                    "You are a structured data extractor. Output ONLY valid JSON. No explanation, no markdown."),
                java.util.Map.of("role", "user", "content", prompt)
            );
        }
    }
}
