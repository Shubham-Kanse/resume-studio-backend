package com.resumestudio.reviewer.nlg;

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
 * Loads sentence_bank.json and provides tiered, interpolated sentence generation.
 *
 * Each signal has 5 tiers: EXCELLENT, GOOD, FAIR, POOR, CRITICAL.
 * Each tier has multiple observation/interpretation/action variants — one is picked
 * pseudo-randomly (seeded by signal+tier for determinism per resume).
 *
 * Token interpolation: {{candidate_title}}, {{jd_title}}, {{calculated_yoe}}, etc.
 */
@Component
public class SentenceBankOntologyService {

    private static final Logger log = LoggerFactory.getLogger(SentenceBankOntologyService.class);

    @Value("classpath:taxonomy/sentence_bank.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();

    // signalId → tier → TierEntry
    private final Map<String, Map<String, TierEntry>> signals = new HashMap<>();

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());
            JsonNode signalsNode = root.path("signals");
            signalsNode.fields().forEachRemaining(e -> {
                String signalId = e.getKey();
                Map<String, TierEntry> tiers = new LinkedHashMap<>();
                e.getValue().path("tiers").fields().forEachRemaining(t -> {
                    TierEntry entry = new TierEntry();
                    entry.tier = t.getKey();
                    entry.observations = toList(t.getValue().path("observation"));
                    entry.interpretations = toList(t.getValue().path("interpretation"));
                    entry.actions = toList(t.getValue().path("action"));
                    tiers.put(t.getKey(), entry);
                });
                signals.put(signalId, tiers);
            });
            log.info("SentenceBankOntologyService loaded: {} signals", signals.size());
        } catch (Exception e) {
            log.warn("SentenceBankOntologyService: failed to load sentence_bank.json — {}", e.getMessage());
        }
    }

    /**
     * Get an observation sentence for a signal at a given tier, with token substitution.
     * Picks a variant based on a seed for determinism (same resume = same sentence).
     */
    public String observation(String signalId, String tier, Map<String, String> tokens, int seed) {
        return pick(signalId, tier, "observation", tokens, seed);
    }

    public String interpretation(String signalId, String tier, Map<String, String> tokens, int seed) {
        return pick(signalId, tier, "interpretation", tokens, seed);
    }

    public String action(String signalId, String tier, Map<String, String> tokens, int seed) {
        return pick(signalId, tier, "action", tokens, seed);
    }

    /** True if this signal+tier combination exists in the ontology. */
    public boolean has(String signalId, String tier) {
        Map<String, TierEntry> tiers = signals.get(signalId);
        return tiers != null && tiers.containsKey(tier);
    }

    // ── Tier mapping helpers ──────────────────────────────────────────────────

    /** Maps PASS/WARN/FAIL + context to EXCELLENT/GOOD/FAIR/POOR/CRITICAL. */
    public static String toTier(String status, boolean isStrong) {
        return switch (status) {
            case "PASS" -> isStrong ? "EXCELLENT" : "GOOD";
            case "WARN" -> isStrong ? "FAIR" : "POOR";
            case "FAIL" -> "CRITICAL";
            default -> "FAIR";
        };
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private String pick(String signalId, String tier, String field, Map<String, String> tokens, int seed) {
        Map<String, TierEntry> tiers = signals.get(signalId);
        if (tiers == null) return null;
        TierEntry entry = tiers.get(tier);
        if (entry == null) return null;

        List<String> variants = switch (field) {
            case "observation" -> entry.observations;
            case "interpretation" -> entry.interpretations;
            case "action" -> entry.actions;
            default -> List.of();
        };
        if (variants.isEmpty()) return null;

        String raw = variants.get(Math.abs(seed) % variants.size());
        return interpolate(raw, tokens);
    }

    private String interpolate(String template, Map<String, String> tokens) {
        if (template == null || tokens == null) return template;
        String result = template;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue() != null ? e.getValue() : "");
        }
        return result;
    }

    private List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(item.asText());
        return result;
    }

    public static class TierEntry {
        public String tier;
        public List<String> observations = List.of();
        public List<String> interpretations = List.of();
        public List<String> actions = List.of();
    }
}
