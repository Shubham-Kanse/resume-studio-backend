package com.resumestudio.reviewer.nlp;

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
 * Loads soft_skills_ontology.json.
 * Replaces the hardcoded SOFT_SKILLS set in SkillsSectionExtractor.
 * Also used for summary quality scoring and bullet signal detection.
 */
@Component
public class SoftSkillsService {

    private static final Logger log = LoggerFactory.getLogger(SoftSkillsService.class);

    @Value("classpath:taxonomy/soft_skills_ontology.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();
    // match phrase (lowercased) → entry
    private final Map<String, PhraseEntry> matchIndex = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());
            for (JsonNode node : root.path("phrases")) {
                PhraseEntry entry = new PhraseEntry();
                entry.key = node.path("key").asText();
                entry.category = node.path("category").asText(null);
                entry.subcategory = node.path("subcategory").asText(null);
                entry.matchForms = toList(node.path("match"));
                JsonNode score = node.path("score");
                entry.type = score.path("type").asText("WEAK_SIGNAL");
                entry.baseWeight = score.path("baseWeight").asDouble(0.0);
                entry.bonusIfOutcomePresent = score.path("bonusIfOutcomePresent").asDouble(0.0);
                entry.bonusIfScopePresent = score.path("bonusIfScopePresent").asDouble(0.0);
                entry.bonusIfToolNamed = score.path("bonusIfToolNamed").asDouble(0.0);
                JsonNode fb = node.path("feedback");
                entry.suggestion = fb.path("suggestion").asText(null);
                entry.exampleStrong = fb.path("exampleStrong").asText(null);
                entry.impliesSeniority = toList(fb.path("impliesSeniority"));
                entry.redFlag = fb.path("redFlag").asBoolean(false);
                entry.preserveIf = fb.path("preserveIf").asText(null);

                for (String form : entry.matchForms) {
                    matchIndex.put(form.toLowerCase(), entry);
                }
            }
            log.info("SoftSkillsService loaded: {} phrase forms", matchIndex.size());
        } catch (Exception e) {
            log.warn("SoftSkillsService: failed to load soft_skills_ontology.json — {}", e.getMessage());
        }
    }

    /** Returns first matching entry found in text, or null. */
    public PhraseEntry lookup(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase();
        for (Map.Entry<String, PhraseEntry> e : matchIndex.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** Returns all matching entries found in text. */
    public List<PhraseEntry> findAll(String text) {
        if (text == null) return List.of();
        String lower = text.toLowerCase();
        Set<String> seen = new HashSet<>();
        List<PhraseEntry> results = new ArrayList<>();
        for (Map.Entry<String, PhraseEntry> e : matchIndex.entrySet()) {
            if (lower.contains(e.getKey()) && seen.add(e.getValue().key)) {
                results.add(e.getValue());
            }
        }
        return results;
    }

    public boolean isRedFlag(String text) {
        return findAll(text).stream().anyMatch(e -> e.redFlag);
    }

    public boolean isLeadershipSignal(String text) {
        return findAll(text).stream().anyMatch(e ->
            "LEADERSHIP_SIGNAL".equals(e.type) || "OWNERSHIP_SIGNAL".equals(e.type));
    }

    public double scoreText(String text, boolean hasOutcome, boolean hasScope) {
        return findAll(text).stream().mapToDouble(e -> {
            double w = e.baseWeight;
            if (hasOutcome) w += e.bonusIfOutcomePresent;
            if (hasScope) w += e.bonusIfScopePresent;
            return w;
        }).sum();
    }

    public boolean isBuzzword(String text) {
        return findAll(text).stream().anyMatch(e ->
            "BUZZWORD".equals(e.type) || "NEGATIVE_SIGNAL".equals(e.type));
    }

    /** True if the text contains a known soft skill phrase (any type). Used to replace SOFT_SKILLS set. */
    public boolean isSoftSkill(String skillName) {
        if (skillName == null) return false;
        return matchIndex.containsKey(skillName.toLowerCase().trim());
    }

    private List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(item.asText());
        return result;
    }

    public static class PhraseEntry {
        public String key;
        public String category;
        public String subcategory;
        public List<String> matchForms = List.of();
        public String type;
        public double baseWeight;
        public double bonusIfOutcomePresent;
        public double bonusIfScopePresent;
        public double bonusIfToolNamed;
        public String suggestion;
        public String exampleStrong;
        public List<String> impliesSeniority = List.of();
        public boolean redFlag;
        public String preserveIf;

        public String getKey() { return key; }
        public String getCategory() { return category; }
        public String getType() { return type; }
        public double getBaseWeight() { return baseWeight; }
        public boolean isRedFlag() { return redFlag; }
        public String getSuggestion() { return suggestion; }
        public List<String> getImpliesSeniority() { return impliesSeniority; }
    }
}
