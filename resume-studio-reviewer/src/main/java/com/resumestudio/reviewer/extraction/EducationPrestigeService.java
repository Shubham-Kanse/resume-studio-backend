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
 * Loads education_prestige_ontology.json.
 * Provides institution tier lookup and degree relevance scoring.
 */
@Component
public class EducationPrestigeService {

    private static final Logger log = LoggerFactory.getLogger(EducationPrestigeService.class);

    @Value("classpath:taxonomy/education_prestige_ontology.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, InstitutionEntry> institutionIndex = new HashMap<>();
    private final Map<String, DegreeEntry> degreeIndex = new HashMap<>();

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());

            for (JsonNode node : root.path("institutions")) {
                InstitutionEntry entry = new InstitutionEntry();
                entry.key = node.path("key").asText();
                entry.matchForms = toList(node.path("match"));
                entry.tier = node.path("tier").asText("UNKNOWN");
                entry.country = node.path("country").asText(null);
                entry.baseBoost = node.path("baseBoost").asDouble(0.5);
                entry.knownFor = toList(node.path("knownFor"));
                for (String form : entry.matchForms) {
                    institutionIndex.put(form.toLowerCase(), entry);
                }
            }

            for (JsonNode node : root.path("degrees")) {
                DegreeEntry entry = new DegreeEntry();
                entry.key = node.path("key").asText();
                entry.matchForms = toList(node.path("match"));
                entry.level = node.path("level").asText(null);
                entry.relevanceToTech = node.path("relevanceToTech").asDouble(0.5);
                entry.baseBoost = node.path("baseBoost").asDouble(0.5);
                for (String form : entry.matchForms) {
                    degreeIndex.put(form.toLowerCase(), entry);
                }
            }

            log.info("EducationPrestigeService loaded: {} institutions, {} degrees",
                institutionIndex.size(), degreeIndex.size());
        } catch (Exception e) {
            log.warn("EducationPrestigeService: failed to load education_prestige_ontology.json — {}", e.getMessage());
        }
    }

    public InstitutionEntry lookupInstitution(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase().trim();
        InstitutionEntry direct = institutionIndex.get(lower);
        if (direct != null) return direct;
        for (Map.Entry<String, InstitutionEntry> e : institutionIndex.entrySet()) {
            if (lower.contains(e.getKey()) || e.getKey().contains(lower)) return e.getValue();
        }
        return null;
    }

    public DegreeEntry lookupDegree(String degreeText) {
        if (degreeText == null) return null;
        String lower = degreeText.toLowerCase().trim();
        DegreeEntry direct = degreeIndex.get(lower);
        if (direct != null) return direct;
        for (Map.Entry<String, DegreeEntry> e : degreeIndex.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    public double institutionBoost(String name) {
        InstitutionEntry e = lookupInstitution(name);
        return e != null ? e.baseBoost : 0.5;
    }

    public double degreeRelevance(String degreeText) {
        DegreeEntry e = lookupDegree(degreeText);
        return e != null ? e.relevanceToTech : 0.5;
    }

    public String institutionTier(String name) {
        InstitutionEntry e = lookupInstitution(name);
        return e != null ? e.tier : "UNKNOWN";
    }

    public boolean isEliteOrPrestige(String name) {
        String tier = institutionTier(name);
        return "ELITE".equals(tier) || "PRESTIGE".equals(tier);
    }

    private List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(item.asText());
        return result;
    }

    public static class InstitutionEntry {
        public String key;
        public List<String> matchForms = List.of();
        public String tier;
        public String country;
        public double baseBoost;
        public List<String> knownFor = List.of();

        public String getKey() { return key; }
        public String getTier() { return tier; }
        public double getBaseBoost() { return baseBoost; }
        public List<String> getKnownFor() { return knownFor; }
    }

    public static class DegreeEntry {
        public String key;
        public List<String> matchForms = List.of();
        public String level;
        public double relevanceToTech;
        public double baseBoost;

        public String getKey() { return key; }
        public String getLevel() { return level; }
        public double getRelevanceToTech() { return relevanceToTech; }
        public double getBaseBoost() { return baseBoost; }
    }
}
