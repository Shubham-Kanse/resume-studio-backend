package com.resumestudio.reviewer.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Loads it_designation_ontology.json and provides:
 *   - isKnownTitle(text)       — replaces looksLikeTitle keyword list
 *   - canonicalise(title)      — "Sr. SWE" → "Senior Software Engineer"
 *   - seniorityLevel(title)    — 1-7 numeric level
 *   - domain(title)            — "Backend", "DevOps", etc.
 *   - relatedDesignations(title)
 *   - canProgressTo(title)
 *   - inferDomain(roleTitle)   — replaces ReviewerPipeline.inferDomain heuristic
 */
@Component
public class DesignationOntologyService {

    private static final Logger log = LoggerFactory.getLogger(DesignationOntologyService.class);

    @Value("classpath:taxonomy/it_designation_ontology.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();

    // synonym (lowercase) → canonical name
    private final Map<String, String> synonymIndex = new HashMap<>();
    // canonical name → full entry
    private final Map<String, DesignationEntry> entryIndex = new HashMap<>();

    private static final Map<String, Integer> SENIORITY_LEVEL = new LinkedHashMap<>();
    static {
        SENIORITY_LEVEL.put("Intern",        1);
        SENIORITY_LEVEL.put("Junior",        2);
        SENIORITY_LEVEL.put("Mid",           3);
        SENIORITY_LEVEL.put("Senior",        4);
        SENIORITY_LEVEL.put("Lead",          4);
        SENIORITY_LEVEL.put("Staff",         5);
        SENIORITY_LEVEL.put("Principal",     6);
        SENIORITY_LEVEL.put("Distinguished", 6);
        SENIORITY_LEVEL.put("Fellow",        7);
        SENIORITY_LEVEL.put("Manager",       4);
        SENIORITY_LEVEL.put("Director",      5);
        SENIORITY_LEVEL.put("VP",            6);
        SENIORITY_LEVEL.put("C-Level",       7);
    }

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());
            JsonNode designations = root.path("designations");
            for (JsonNode node : designations) {
                String name = node.path("name").asText();
                DesignationEntry entry = new DesignationEntry();
                entry.name = name;
                entry.domains = toList(node.path("domains"));
                entry.seniorityRange = toList(node.path("seniorityRange"));
                entry.relatedDesignations = toList(node.path("relatedDesignations"));
                entry.canProgressTo = toList(node.path("canProgressTo"));
                entry.impliesKnowing = toList(node.path("impliesKnowingDesignations"));

                entryIndex.put(name.toLowerCase(), entry);

                // Index all synonyms
                for (JsonNode syn : node.path("synonyms")) {
                    synonymIndex.put(syn.asText().toLowerCase(), name.toLowerCase());
                }
                // Also index seniorityVariants
                for (JsonNode v : node.path("seniorityVariants")) {
                    synonymIndex.put(v.asText().toLowerCase(), name.toLowerCase());
                }
                // Self-index
                synonymIndex.put(name.toLowerCase(), name.toLowerCase());
            }
            log.info("DesignationOntology loaded: {} designations, {} synonyms",
                entryIndex.size(), synonymIndex.size());
        } catch (Exception e) {
            log.warn("Failed to load it_designation_ontology.json: {}", e.getMessage());
        }
    }

    /** True if the text (or any substring) matches a known designation synonym. */
    public boolean isKnownTitle(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase().trim();
        // Direct synonym match
        if (synonymIndex.containsKey(lower)) return true;
        // Partial: check if any known synonym is contained in the text
        for (String syn : synonymIndex.keySet()) {
            if (syn.length() > 3 && lower.contains(syn)) return true;
        }
        return false;
    }

    /** Resolve a raw title to its canonical designation name, or null if unknown. */
    public String canonicalise(String title) {
        if (title == null) return null;
        String lower = title.toLowerCase().trim();
        String canonical = synonymIndex.get(lower);
        if (canonical != null) return entryIndex.get(canonical).name;
        // Try stripping seniority prefix and matching the base role
        for (String prefix : List.of("senior ", "sr. ", "sr ", "junior ", "jr. ", "jr ",
                "staff ", "principal ", "lead ", "associate ")) {
            if (lower.startsWith(prefix)) {
                String base = lower.substring(prefix.length()).trim();
                String baseCanonical = synonymIndex.get(base);
                if (baseCanonical != null) return entryIndex.get(baseCanonical).name;
            }
        }
        return null;
    }

    /**
     * Numeric seniority level 1-7.
     * Derived from the designation's seniorityRange — takes the highest level in range.
     * Falls back to seniority keyword scan on the raw title.
     */
    public int seniorityLevel(String title) {
        if (title == null) return 3;
        String lower = title.toLowerCase().trim();
        String canonical = synonymIndex.get(lower);
        if (canonical != null) {
            DesignationEntry entry = entryIndex.get(canonical);
            return entry.seniorityRange.stream()
                .mapToInt(s -> SENIORITY_LEVEL.getOrDefault(s, 3))
                .max().orElse(3);
        }
        // Fallback: scan for seniority keywords in the raw title
        for (Map.Entry<String, Integer> e : SENIORITY_LEVEL.entrySet()) {
            if (lower.contains(e.getKey().toLowerCase())) return e.getValue();
        }
        return 3; // default mid
    }

    /** Primary domain of the designation, or null. */
    public String primaryDomain(String title) {
        if (title == null) return null;
        String canonical = synonymIndex.get(title.toLowerCase().trim());
        if (canonical == null) return null;
        List<String> domains = entryIndex.get(canonical).domains;
        return domains.isEmpty() ? null : domains.get(0);
    }

    /** All domains for a designation. */
    public List<String> domains(String title) {
        if (title == null) return List.of();
        String canonical = synonymIndex.get(title.toLowerCase().trim());
        if (canonical == null) return List.of();
        return entryIndex.get(canonical).domains;
    }

    public List<String> relatedDesignations(String title) {
        if (title == null) return List.of();
        String canonical = synonymIndex.get(title.toLowerCase().trim());
        if (canonical == null) return List.of();
        return entryIndex.get(canonical).relatedDesignations;
    }

    public List<String> canProgressTo(String title) {
        if (title == null) return List.of();
        String canonical = synonymIndex.get(title.toLowerCase().trim());
        if (canonical == null) return List.of();
        return entryIndex.get(canonical).canProgressTo;
    }

    /**
     * Infer a human-readable domain label from a role title.
     * Replaces the hardcoded heuristic in ReviewerPipeline.inferDomain.
     */
    public String inferDomain(String roleTitle) {
        if (roleTitle == null) return "Software Engineering";
        String domain = primaryDomain(roleTitle);
        if (domain != null) return domain;
        // Fallback: keyword scan
        String lower = roleTitle.toLowerCase();
        if (lower.contains("backend") || lower.contains("java") || lower.contains("python") || lower.contains("api")) return "Backend Engineering";
        if (lower.contains("frontend") || lower.contains("react") || lower.contains("ui")) return "Frontend Engineering";
        if (lower.contains("full stack") || lower.contains("fullstack")) return "Full Stack Engineering";
        if (lower.contains("devops") || lower.contains("sre") || lower.contains("platform") || lower.contains("infrastructure")) return "DevOps / Platform";
        if (lower.contains("data") || lower.contains("ml") || lower.contains("machine learning") || lower.contains("ai")) return "Data / ML";
        if (lower.contains("mobile") || lower.contains("ios") || lower.contains("android")) return "Mobile Engineering";
        if (lower.contains("security") || lower.contains("infosec")) return "Security";
        if (lower.contains("qa") || lower.contains("test") || lower.contains("sdet")) return "Quality Engineering";
        if (lower.contains("product")) return "Product Management";
        if (lower.contains("manager") || lower.contains("director")) return "Engineering Management";
        return "Software Engineering";
    }

    private List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(item.asText());
        return result;
    }

    public static class DesignationEntry {
        public String name;
        public List<String> domains = List.of();
        public List<String> seniorityRange = List.of();
        public List<String> relatedDesignations = List.of();
        public List<String> canProgressTo = List.of();
        public List<String> impliesKnowing = List.of();
    }
}
