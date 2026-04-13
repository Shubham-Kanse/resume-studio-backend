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
 * Loads jd_role_patterns.json.
 * Provides implicit expectation inference, typical YOE ranges, must-have groups,
 * and JD quality signals (red flags, green flags, clarity indicators).
 */
@Component
public class JdRolePatternsService {

    private static final Logger log = LoggerFactory.getLogger(JdRolePatternsService.class);

    @Value("classpath:taxonomy/jd_role_patterns.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();
    // match string (lowercased) → role pattern
    private final Map<String, RolePattern> matchIndex = new HashMap<>();
    private List<String> globalRedFlags = new ArrayList<>();
    private List<String> globalGreenFlags = new ArrayList<>();

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());

            JsonNode global = root.path("globalConfig");
            globalRedFlags = toList(global.path("globalRedFlags"));
            globalGreenFlags = toList(global.path("globalGreenFlags"));

            for (JsonNode node : root.path("roles")) {
                RolePattern rp = new RolePattern();
                rp.key = node.path("key").asText();
                rp.category = node.path("category").asText(null);
                rp.matchPatterns = toList(node.path("match"));

                JsonNode score = node.path("score");
                JsonNode yoe = score.path("typicalYoeRange");
                if (!yoe.isMissingNode()) {
                    rp.typicalYoeMin = yoe.path("min").asDouble(0);
                    rp.typicalYoeMax = yoe.path("max").asDouble(10);
                }
                rp.mustHaveGroups = toListOfLists(score.path("mustHaveGroups"));
                rp.niceToHave = toList(score.path("niceToHave"));
                rp.implicitExpectations = toList(score.path("implicitExpectations"));

                JsonNode fb = node.path("feedback");
                rp.redFlagPhrases = toList(fb.path("redFlagPhrases"));
                rp.greenFlagPhrases = toList(fb.path("greenFlagPhrases"));
                JsonNode clarity = fb.path("jdClarityIndicators");
                rp.highClarityIndicators = toList(clarity.path("highClarity"));
                rp.lowClarityIndicators = toList(clarity.path("lowClarity"));

                for (String m : rp.matchPatterns) {
                    matchIndex.put(m.toLowerCase(), rp);
                }
            }
            log.info("JdRolePatternsService loaded: {} role patterns, {} red flags",
                matchIndex.size(), globalRedFlags.size());
        } catch (Exception e) {
            log.warn("JdRolePatternsService: failed to load jd_role_patterns.json — {}", e.getMessage());
        }
    }

    /** Finds the best matching role pattern for a JD title. Longest match wins. */
    public RolePattern matchRole(String jdTitle) {
        if (jdTitle == null) return null;
        String lower = jdTitle.toLowerCase();
        RolePattern best = null;
        int bestLen = 0;
        for (Map.Entry<String, RolePattern> e : matchIndex.entrySet()) {
            if (lower.contains(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return best;
    }

    public List<String> implicitExpectations(String jdTitle) {
        RolePattern rp = matchRole(jdTitle);
        return rp != null ? rp.implicitExpectations : List.of();
    }

    public double[] typicalYoeRange(String jdTitle) {
        RolePattern rp = matchRole(jdTitle);
        if (rp == null || rp.typicalYoeMin == null) return null;
        return new double[]{rp.typicalYoeMin, rp.typicalYoeMax};
    }

    public List<List<String>> mustHaveGroups(String jdTitle) {
        RolePattern rp = matchRole(jdTitle);
        return rp != null ? rp.mustHaveGroups : List.of();
    }

    public boolean hasRedFlag(String jdText) {
        if (jdText == null) return false;
        String lower = jdText.toLowerCase();
        if (globalRedFlags.stream().anyMatch(f -> lower.contains(f.toLowerCase()))) return true;
        // Also check role-specific
        for (RolePattern rp : new HashSet<>(matchIndex.values())) {
            if (rp.redFlagPhrases.stream().anyMatch(f -> lower.contains(f.toLowerCase()))) return true;
        }
        return false;
    }

    public boolean hasGreenFlag(String jdText) {
        if (jdText == null) return false;
        String lower = jdText.toLowerCase();
        return globalGreenFlags.stream().anyMatch(f -> lower.contains(f.toLowerCase()));
    }

    public boolean isHighClarity(String jdText, String jdTitle) {
        if (jdText == null) return false;
        String lower = jdText.toLowerCase();
        RolePattern rp = matchRole(jdTitle);
        List<String> indicators = rp != null ? rp.highClarityIndicators : List.of();
        return indicators.stream().anyMatch(i -> lower.contains(i.toLowerCase()));
    }

    private List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return new ArrayList<>();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(item.asText());
        return result;
    }

    private List<List<String>> toListOfLists(JsonNode node) {
        if (node == null || !node.isArray()) return new ArrayList<>();
        List<List<String>> result = new ArrayList<>();
        for (JsonNode group : node) result.add(toList(group));
        return result;
    }

    public static class RolePattern {
        public String key;
        public String category;
        public List<String> matchPatterns = List.of();
        public Double typicalYoeMin;
        public Double typicalYoeMax;
        public List<List<String>> mustHaveGroups = List.of();
        public List<String> niceToHave = List.of();
        public List<String> implicitExpectations = List.of();
        public List<String> redFlagPhrases = List.of();
        public List<String> greenFlagPhrases = List.of();
        public List<String> highClarityIndicators = List.of();
        public List<String> lowClarityIndicators = List.of();

        public String getKey() { return key; }
        public String getCategory() { return category; }
        public List<String> getImplicitExpectations() { return implicitExpectations; }
        public List<List<String>> getMustHaveGroups() { return mustHaveGroups; }
        public List<String> getNiceToHave() { return niceToHave; }
    }
}
