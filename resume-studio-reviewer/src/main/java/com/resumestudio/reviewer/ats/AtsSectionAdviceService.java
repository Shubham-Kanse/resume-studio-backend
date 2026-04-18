package com.resumestudio.reviewer.ats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads resume_quality_ontology.json and enriches each QualitySection with:
 *  - recruiterTip  : observation sentence for the section's score tier
 *  - beforeExample : first real bullet extracted from the section's issues list
 *  - foundItems    : specific fillers / buzzwords / weak verbs found in this resume
 */
@Service
public class AtsSectionAdviceService {

    private static final Logger log = LoggerFactory.getLogger(AtsSectionAdviceService.class);

    @Value("classpath:taxonomy/resume_quality_ontology.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();

    // signalId → tier → observation[]
    private final Map<String, Map<String, List<String>>> observations = new HashMap<>();

    // Maps QualitySection id → ontology signal_id
    private static final Map<String, String> SECTION_TO_SIGNAL = Map.ofEntries(
        Map.entry("quantifying-impact",  "ats_quantify_impact"),
        Map.entry("action-verb-use",      "ats_action_verbs"),
        Map.entry("accomplishments",      "ats_accomplishments"),
        Map.entry("repetition",           "ats_repetition"),
        Map.entry("length",               "ats_length"),
        Map.entry("filler-words",         "ats_filler_words"),
        Map.entry("total-bullet-points",  "ats_bullet_count"),
        Map.entry("bullet-points-length", "ats_bullet_length"),
        Map.entry("sections",             "ats_sections"),
        Map.entry("personal-pronouns",    "ats_pronouns"),
        Map.entry("buzzwords-cliches",    "ats_buzzwords"),
        Map.entry("active-voice",         "ats_active_voice"),
        Map.entry("consistency",          "ats_consistency"),
        Map.entry("spell-check",          "ats_spell_check"),
        Map.entry("date-order",           "ats_date_order")
    );

    // Pattern to extract quoted items from issue strings: Filler phrase 'xyz': ...
    private static final Pattern QUOTED_ITEM = Pattern.compile("'([^']+)'");

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());
            JsonNode registry = root.path("signal_registry");
            registry.fields().forEachRemaining(e -> {
                String signalId = e.getKey();
                Map<String, List<String>> tiers = new LinkedHashMap<>();
                e.getValue().path("tiers").fields().forEachRemaining(t -> {
                    List<String> obs = new ArrayList<>();
                    t.getValue().path("observation").forEach(n -> obs.add(n.asText()));
                    tiers.put(t.getKey(), obs);
                });
                observations.put(signalId, tiers);
            });
            log.info("AtsSectionAdviceService loaded: {} signals", observations.size());
        } catch (Exception e) {
            log.warn("AtsSectionAdviceService: failed to load resume_quality_ontology.json — {}", e.getMessage());
        }
    }

    /**
     * Enrich a QualitySection with NLG tip, before-example, and found items.
     * Returns a new QualitySection with the three new fields populated.
     */
    public AtsScoreResponse.QualitySection advise(AtsScoreResponse.QualitySection qs) {
        String signalId = SECTION_TO_SIGNAL.get(qs.id());
        String recruiterTip = pickObservation(signalId, scoreToTier(qs.score()), qs.score());
        String beforeExample = extractBeforeExample(qs.issues());
        List<String> foundItems = extractFoundItems(qs.issues());

        return new AtsScoreResponse.QualitySection(
            qs.id(), qs.label(), qs.score(), qs.severity(),
            qs.issues(), qs.suggestion(),
            qs.quantifiedBullets(), qs.totalBullets(), qs.averageBulletLength(),
            recruiterTip, beforeExample, foundItems
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static String scoreToTier(int score) {
        if (score >= 80) return "EXCELLENT";
        if (score >= 60) return "GOOD";
        if (score >= 40) return "FAIR";
        if (score >= 20) return "POOR";
        return "CRITICAL";
    }

    private String pickObservation(String signalId, String tier, int score) {
        if (signalId == null) return null;
        Map<String, List<String>> tiers = observations.get(signalId);
        if (tiers == null) return null;
        List<String> variants = tiers.get(tier);
        if (variants == null || variants.isEmpty()) return null;
        // Deterministic pick based on score so same score always returns same sentence
        return variants.get(score % variants.size());
    }

    /**
     * Extract the first real bullet text from an issue string.
     * Issue format: "Filler phrase 'xyz': <bullet text here>"
     *               "Passive voice: <bullet text here>"
     *               "Too short (N words): <bullet text here>"
     */
    private String extractBeforeExample(List<String> issues) {
        if (issues == null || issues.isEmpty()) return null;
        for (String issue : issues) {
            int colonIdx = issue.indexOf(": ");
            if (colonIdx == -1) continue;
            String text = issue.substring(colonIdx + 2).trim();
            if (text.length() > 15) return text;
        }
        return null;
    }

    /**
     * Extract specific items found in this resume from issue strings.
     * Looks for single-quoted items: Filler phrase 'responsible for': ...
     */
    private List<String> extractFoundItems(List<String> issues) {
        if (issues == null || issues.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String issue : issues) {
            Matcher m = QUOTED_ITEM.matcher(issue);
            if (m.find()) seen.add(m.group(1));
        }
        return new ArrayList<>(seen);
    }
}
