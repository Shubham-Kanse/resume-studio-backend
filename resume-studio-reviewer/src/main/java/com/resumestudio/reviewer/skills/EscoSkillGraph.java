package com.resumestudio.reviewer.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two responsibilities:
 *
 * 1. LOCAL skill dictionary — delegates to MindTechOntology for resolve/isKnownSkill/category.
 *    Replaces the old broken esco-index-v1.bin.
 *
 * 2. ESCO REST API — skill equivalence lookup (areEquivalent).
 *    Used in Layer 3 Step 5 of the matching pipeline.
 *    Results cached in-memory for JVM lifetime.
 */
@Component
public class EscoSkillGraph {

    private static final Logger log = LoggerFactory.getLogger(EscoSkillGraph.class);
    private static final String ESCO_BASE = "https://esco.ec.europa.eu/api";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final MindTechOntology mind;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // ESCO API caches
    private final Map<String, Set<String>> conceptCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> equivCache = new ConcurrentHashMap<>();

    public EscoSkillGraph(MindTechOntology mind) {
        this.mind = mind;
    }

    // ── Local dictionary methods (used by existing pipeline) ─────────────────

    // Common abbreviations not in MIND taxonomy
    private static final Map<String, String> ABBREV = Map.ofEntries(
        Map.entry("js", "JavaScript"),
        Map.entry("ts", "TypeScript"),
        Map.entry("py", "Python"),
        Map.entry("k8s", "Kubernetes"),
        Map.entry("aws", "Amazon Web Services"),
        Map.entry("gcp", "Google Cloud Platform"),
        Map.entry("pg", "PostgreSQL"),
        Map.entry("postgres", "PostgreSQL"),
        Map.entry("mongo", "MongoDB"),
        Map.entry("node", "Node.js"),
        Map.entry("vanilla js", "JavaScript"),
        Map.entry("react.js", "React"),
        Map.entry("reactjs", "React"),
        Map.entry("vue.js", "Vue"),
        Map.entry("vuejs", "Vue"),
        Map.entry("next.js", "Next.js"),
        Map.entry("express.js", "Express"),
        Map.entry("ml", "Machine Learning"),
        Map.entry("ai", "Artificial Intelligence"),
        Map.entry("ci/cd", "CI/CD"),
        Map.entry("cicd", "CI/CD")
    );

    /** Expand an abbreviation to its full form, or return the input unchanged. */
    public String expandAbbreviation(String term) {
        if (term == null) return term;
        return ABBREV.getOrDefault(term.toLowerCase().trim(), term);
    }

    /** Resolve a skill name or synonym to its canonical form. Delegates to MIND, then abbreviation table. */
    public String resolve(String skillName) {
        if (skillName == null) return skillName;
        String resolved = mind.resolve(skillName);
        if (resolved != null) return resolved;
        // Abbreviation fallback
        String lower = skillName.toLowerCase().trim();
        return ABBREV.getOrDefault(lower, skillName);
    }

    /** Check if a skill is known. Delegates to MIND or abbreviation table. */
    public boolean isKnownSkill(String skillName) {
        if (skillName == null) return false;
        return mind.isKnownSkill(skillName) || ABBREV.containsKey(skillName.toLowerCase().trim());
    }

    /** Get skill category. Delegates to MIND type. */
    public String categoryOf(String skillName) {
        List<String> types = mind.getSkillType(skillName);
        return types.isEmpty() ? null : types.get(0);
    }

    /** Returns true if this is a technical skill (has a known MIND type). */
    public boolean isTechnicalSkill(String skillName) {
        return !mind.getSkillType(skillName).isEmpty();
    }

    /** Get related skills. Delegates to MIND implied skills. */
    public List<String> relatedSkills(String skillName) {
        return mind.getImpliedSkills(skillName);
    }

    /**
     * Release year of a technology — used for anomaly detection.
     * Not in MIND ontology; returns null (anomaly detector handles null gracefully).
     */
    public Integer releaseYearOf(String skillName) {
        return null; // not available without the old .bin
    }

    // ── ESCO API equivalence (new) ────────────────────────────────────────────

    /**
     * Returns true if skillA and skillB share at least one ESCO broader concept.
     * e.g. "Java" and "C#" both map to "object-oriented programming languages"
     */
    public boolean areEquivalent(String skillA, String skillB) {
        if (skillA == null || skillB == null) return false;
        String key = cacheKey(skillA, skillB);
        return equivCache.computeIfAbsent(key, k -> computeEquivalence(skillA, skillB));
    }

    public Set<String> getBroaderConcepts(String skill) {
        if (skill == null) return Set.of();
        return conceptCache.computeIfAbsent(skill.toLowerCase(), this::fetchBroaderConcepts);
    }

    private boolean computeEquivalence(String a, String b) {
        Set<String> conceptsA = getBroaderConcepts(a);
        Set<String> conceptsB = getBroaderConcepts(b);
        if (conceptsA.isEmpty() || conceptsB.isEmpty()) return false;
        return !Collections.disjoint(conceptsA, conceptsB);
    }

    private Set<String> fetchBroaderConcepts(String skill) {
        try {
            String searchUrl = ESCO_BASE + "/search?language=en&type=skill&text="
                + URLEncoder.encode(skill, StandardCharsets.UTF_8) + "&limit=1";

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(searchUrl))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET().build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Set.of();

            JsonNode root = mapper.readTree(resp.body());
            JsonNode results = root.path("_embedded").path("results");
            if (!results.isArray() || results.isEmpty()) return Set.of();

            String conceptUri = results.get(0).path("uri").asText();
            if (conceptUri.isBlank()) return Set.of();

            String detailUrl = ESCO_BASE + "/resource/skill?uri="
                + URLEncoder.encode(conceptUri, StandardCharsets.UTF_8) + "&language=en";

            HttpRequest detailReq = HttpRequest.newBuilder()
                .uri(URI.create(detailUrl))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET().build();

            HttpResponse<String> detailResp = http.send(detailReq, HttpResponse.BodyHandlers.ofString());
            if (detailResp.statusCode() != 200) return Set.of();

            JsonNode detail = mapper.readTree(detailResp.body());
            Set<String> concepts = new HashSet<>();

            JsonNode broader = detail.path("_links").path("broaderHierarchyConcept");
            if (broader.isArray()) {
                for (JsonNode node : broader) {
                    String title = node.path("title").asText();
                    if (!title.isBlank()) concepts.add(title.toLowerCase());
                }
            }

            log.debug("ESCO concepts for '{}': {}", skill, concepts);
            return concepts;

        } catch (Exception e) {
            log.debug("ESCO lookup failed for '{}': {}", skill, e.getMessage());
            return Set.of();
        }
    }

    private String cacheKey(String a, String b) {
        String la = a.toLowerCase(), lb = b.toLowerCase();
        return la.compareTo(lb) <= 0 ? la + "|" + lb : lb + "|" + la;
    }
}
