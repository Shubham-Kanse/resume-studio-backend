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
 * Loads resume_ontology.json and exposes:
 *   - sectionSynonyms per field (replaces hardcoded header sets in SemanticExtractor)
 *   - negativePatterns per field (what to reject during extraction)
 *   - validation rules (minWords, minChars for summary)
 *   - disambiguationRules for name extraction
 *   - entryStartPatterns for experience block splitting
 */
@Component
public class ResumeOntologyService {

    private static final Logger log = LoggerFactory.getLogger(ResumeOntologyService.class);

    @Value("classpath:taxonomy/resume_ontology.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();

    // fieldId → set of section synonyms (lowercased)
    private final Map<String, Set<String>> sectionSynonyms = new HashMap<>();
    // fieldId → list of negative patterns (lowercased)
    private final Map<String, List<String>> negativePatterns = new HashMap<>();
    // fieldId → validation map
    private final Map<String, Map<String, Object>> validation = new HashMap<>();
    // fieldId → disambiguation rules
    private final Map<String, List<String>> disambiguationRules = new HashMap<>();
    // WORK_EXPERIENCE entry start patterns
    private List<String> experienceEntryPatterns = new ArrayList<>();

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());
            for (JsonNode node : root) {
                String fieldId = node.path("fieldId").asText(null);
                if (fieldId == null) continue;

                // Section synonyms
                Set<String> syns = new LinkedHashSet<>();
                for (JsonNode s : node.path("sectionSynonyms")) syns.add(s.asText().toLowerCase());
                if (!syns.isEmpty()) sectionSynonyms.put(fieldId, syns);

                // Negative patterns
                List<String> negs = new ArrayList<>();
                for (JsonNode n : node.path("negativePatterns")) negs.add(n.asText().toLowerCase());
                if (!negs.isEmpty()) negativePatterns.put(fieldId, negs);

                // Validation
                JsonNode val = node.path("validation");
                if (!val.isMissingNode()) {
                    Map<String, Object> valMap = new HashMap<>();
                    val.fields().forEachRemaining(e -> valMap.put(e.getKey(), e.getValue().asText()));
                    validation.put(fieldId, valMap);
                }

                // Disambiguation rules
                List<String> rules = new ArrayList<>();
                for (JsonNode r : node.path("disambiguationRules")) {
                    String rule = r.path("rule").asText(null);
                    if (rule != null) rules.add(rule);
                }
                if (!rules.isEmpty()) disambiguationRules.put(fieldId, rules);

                // Experience entry patterns
                if ("WORK_EXPERIENCE".equals(fieldId)) {
                    for (JsonNode p : node.path("entryDetection").path("entryStartPatterns")) {
                        experienceEntryPatterns.add(p.asText());
                    }
                }
            }
            log.info("ResumeOntologyService loaded: {} field definitions", sectionSynonyms.size());
        } catch (Exception e) {
            log.warn("ResumeOntologyService: failed to load resume_ontology.json — {}", e.getMessage());
        }
    }

    /** All section header synonyms for a given field (e.g. "PROFESSIONAL_SUMMARY"). */
    public Set<String> getSectionSynonyms(String fieldId) {
        return sectionSynonyms.getOrDefault(fieldId, Set.of());
    }

    /** Negative patterns — strings that indicate a line is NOT this field. */
    public List<String> getNegativePatterns(String fieldId) {
        return negativePatterns.getOrDefault(fieldId, List.of());
    }

    /** Minimum word count for a field to be considered valid. */
    public int getMinWords(String fieldId) {
        Map<String, Object> val = validation.get(fieldId);
        if (val == null) return 0;
        try { return Integer.parseInt(val.getOrDefault("minWords", "0").toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Minimum character count for a field. */
    public int getMinChars(String fieldId) {
        Map<String, Object> val = validation.get(fieldId);
        if (val == null) return 0;
        try { return Integer.parseInt(val.getOrDefault("minChars", "0").toString()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** True if the text contains any negative pattern for this field. */
    public boolean matchesNegativePattern(String fieldId, String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return getNegativePatterns(fieldId).stream().anyMatch(lower::contains);
    }

    public List<String> getExperienceEntryPatterns() { return experienceEntryPatterns; }
}
