package com.resumestudio.reviewer.skills;

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
 * Loads skill_recency_ontology.json.
 * Replaces the hardcoded STALE_TECHNOLOGIES set and the broken releaseYearOf() stub.
 */
@Component
public class SkillRecencyService {

    private static final Logger log = LoggerFactory.getLogger(SkillRecencyService.class);

    @Value("classpath:taxonomy/skill_recency_ontology.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SkillRecencyEntry> matchIndex = new HashMap<>();

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());
            for (JsonNode node : root.path("skills")) {
                SkillRecencyEntry entry = new SkillRecencyEntry();
                entry.key = node.path("key").asText();
                entry.matchForms = toList(node.path("match"));
                entry.category = node.path("category").asText(null);
                entry.born = node.path("born").isNull() ? null : node.path("born").asInt();
                JsonNode score = node.path("score");
                entry.status = score.path("status").asText("ESTABLISHED");
                entry.maxClaimableYears = score.path("maxClaimableYears").asInt(50);
                entry.flagIfClaimedOver = score.path("flagIfClaimedOver").asInt(50);
                entry.staleness = score.path("staleness").asDouble(0.0);

                for (String form : entry.matchForms) {
                    matchIndex.put(form.toLowerCase(), entry);
                }
            }
            log.info("SkillRecencyService loaded: {} skill forms", matchIndex.size());
        } catch (Exception e) {
            log.warn("SkillRecencyService: failed to load skill_recency_ontology.json — {}", e.getMessage());
        }
    }

    public SkillRecencyEntry lookup(String skillName) {
        if (skillName == null) return null;
        return matchIndex.get(skillName.toLowerCase().trim());
    }

    public boolean isStale(String skillName) {
        SkillRecencyEntry e = lookup(skillName);
        if (e == null) return false;
        return "LEGACY".equals(e.status) || "DEPRECATED".equals(e.status);
    }

    public boolean isCurrent(String skillName) {
        SkillRecencyEntry e = lookup(skillName);
        if (e == null) return true; // unknown = assume current
        return "CURRENT".equals(e.status) || "ESTABLISHED".equals(e.status);
    }

    public double stalenessScore(String skillName) {
        SkillRecencyEntry e = lookup(skillName);
        return e != null ? e.staleness : 0.0;
    }

    public boolean isYoeClaimSuspicious(String skillName, int claimedYears) {
        SkillRecencyEntry e = lookup(skillName);
        return e != null && claimedYears > e.flagIfClaimedOver;
    }

    public String status(String skillName) {
        SkillRecencyEntry e = lookup(skillName);
        return e != null ? e.status : null;
    }

    public Integer bornYear(String skillName) {
        SkillRecencyEntry e = lookup(skillName);
        return e != null ? e.born : null;
    }

    private List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(item.asText());
        return result;
    }

    public static class SkillRecencyEntry {
        public String key;
        public List<String> matchForms = List.of();
        public String category;
        public Integer born;
        public String status;
        public int maxClaimableYears;
        public int flagIfClaimedOver;
        public double staleness;

        public String getKey() { return key; }
        public String getStatus() { return status; }
        public Integer getBorn() { return born; }
        public double getStaleness() { return staleness; }
        public int getFlagIfClaimedOver() { return flagIfClaimedOver; }
    }
}
