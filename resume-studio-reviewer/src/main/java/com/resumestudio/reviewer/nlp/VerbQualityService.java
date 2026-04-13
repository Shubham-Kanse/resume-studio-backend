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
 * Loads verb_quality_ontology.json.
 * Replaces the hardcoded IMPACT_VERBS / WEAK_VERBS sets in NlpService and BulletEnricher.
 */
@Component
public class VerbQualityService {

    private static final Logger log = LoggerFactory.getLogger(VerbQualityService.class);

    @Value("classpath:taxonomy/verb_quality_ontology.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();
    // every match form (lowercased) → entry
    private final Map<String, VerbEntry> matchIndex = new HashMap<>();

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());
            for (JsonNode node : root.path("verbs")) {
                VerbEntry entry = new VerbEntry();
                entry.key = node.path("key").asText();
                entry.matchForms = toList(node.path("match"));
                JsonNode score = node.path("score");
                entry.baseWeight = score.path("baseWeight").asDouble(0.5);
                entry.quality = score.path("quality").asText("MODERATE");
                entry.impactDirection = score.path("impactDirection").asText("AMBIGUOUS");
                entry.penaltyIfNoMetric = score.path("penaltyIfNoMetric").asDouble(1.0);
                entry.penaltyIfPassive = score.path("penaltyIfPassive").asDouble(1.0);
                JsonNode fb = node.path("feedback");
                entry.missingMetricMessage = fb.path("missingMetricMessage").asText(null);
                entry.suggestion = fb.path("suggestion").asText(null);
                entry.impliesSeniority = toList(fb.path("impliesSeniority"));

                for (String form : entry.matchForms) {
                    matchIndex.put(form.toLowerCase(), entry);
                }
                // suffix-stripped fallbacks
                for (String form : entry.matchForms) {
                    String lower = form.toLowerCase();
                    if (lower.endsWith("ed") && lower.length() > 4) matchIndex.putIfAbsent(lower.substring(0, lower.length() - 2), entry);
                    if (lower.endsWith("ing") && lower.length() > 5) matchIndex.putIfAbsent(lower.substring(0, lower.length() - 3), entry);
                    if (lower.endsWith("s") && lower.length() > 3) matchIndex.putIfAbsent(lower.substring(0, lower.length() - 1), entry);
                }
            }
            log.info("VerbQualityService loaded: {} verb forms", matchIndex.size());
        } catch (Exception e) {
            log.warn("VerbQualityService: failed to load verb_quality_ontology.json — {}", e.getMessage());
        }
    }

    public VerbEntry lookup(String word) {
        if (word == null) return null;
        return matchIndex.get(word.toLowerCase().trim());
    }

    public double effectiveWeight(String word, boolean hasMetric, boolean isPassive) {
        VerbEntry e = lookup(word);
        if (e == null) return 0.0;
        double w = e.baseWeight;
        if (!hasMetric) w *= e.penaltyIfNoMetric;
        if (isPassive) w *= e.penaltyIfPassive;
        return w;
    }

    public String quality(String word) {
        VerbEntry e = lookup(word);
        return e != null ? e.quality : null;
    }

    public boolean isImpactVerb(String word) {
        String q = quality(word);
        return "ELITE".equals(q) || "STRONG".equals(q) || "GOOD".equals(q);
    }

    public boolean isWeakVerb(String word) {
        String q = quality(word);
        return "WEAK".equals(q) || "TOXIC".equals(q);
    }

    public List<String> impliedSeniority(String word) {
        VerbEntry e = lookup(word);
        return e != null ? e.impliesSeniority : List.of();
    }

    private List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(item.asText());
        return result;
    }

    public static class VerbEntry {
        public String key;
        public List<String> matchForms = List.of();
        public double baseWeight;
        public String quality;
        public String impactDirection;
        public double penaltyIfNoMetric;
        public double penaltyIfPassive;
        public String missingMetricMessage;
        public String suggestion;
        public List<String> impliesSeniority = List.of();

        public String getKey() { return key; }
        public double getBaseWeight() { return baseWeight; }
        public String getQuality() { return quality; }
        public String getImpactDirection() { return impactDirection; }
        public String getMissingMetricMessage() { return missingMetricMessage; }
        public String getSuggestion() { return suggestion; }
        public List<String> getImpliesSeniority() { return impliesSeniority; }
    }
}
