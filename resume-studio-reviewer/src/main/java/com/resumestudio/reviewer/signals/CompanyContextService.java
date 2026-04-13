package com.resumestudio.reviewer.signals;

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
 * Loads company_context_ontology.json.
 * Replaces the flat company-tiers.json name lookup with rich credibility + domain boost data.
 */
@Component
public class CompanyContextService {

    private static final Logger log = LoggerFactory.getLogger(CompanyContextService.class);

    @Value("classpath:taxonomy/company_context_ontology.json")
    private Resource ontologyResource;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CompanyEntry> matchIndex = new HashMap<>();

    private static final Map<String, Integer> TIER_ORDER = Map.of(
        "TIER_0", 6, "TIER_1", 5, "TIER_2", 4, "TIER_3", 3, "TIER_4", 2, "UNKNOWN", 1
    );

    @PostConstruct
    public void load() {
        try {
            JsonNode root = mapper.readTree(ontologyResource.getInputStream());
            for (JsonNode node : root.path("companies")) {
                CompanyEntry entry = new CompanyEntry();
                entry.key = node.path("key").asText();
                entry.matchForms = toList(node.path("match"));
                JsonNode score = node.path("score");
                entry.tier = score.path("tier").asText("UNKNOWN");
                entry.baseCredibility = score.path("baseCredibility").asDouble(0.5);
                entry.domainBoost = new HashMap<>();
                JsonNode boost = score.path("domainBoost");
                if (boost.isObject()) {
                    boost.fields().forEachRemaining(f -> entry.domainBoost.put(f.getKey(), f.getValue().asDouble(1.0)));
                }
                JsonNode fb = node.path("feedback");
                entry.note = fb.path("note").asText(null);
                entry.recentSignals = toList(fb.path("recentSignals"));

                for (String form : entry.matchForms) {
                    matchIndex.put(form.toLowerCase(), entry);
                }
            }
            log.info("CompanyContextService loaded: {} company forms", matchIndex.size());
        } catch (Exception e) {
            log.warn("CompanyContextService: failed to load company_context_ontology.json — {}", e.getMessage());
        }
    }

    public CompanyEntry lookup(String companyName) {
        if (companyName == null) return null;
        String lower = companyName.toLowerCase().trim();
        // Direct match first
        CompanyEntry direct = matchIndex.get(lower);
        if (direct != null) return direct;
        // Partial: check if any known form is contained in the name
        for (Map.Entry<String, CompanyEntry> e : matchIndex.entrySet()) {
            if (lower.contains(e.getKey()) || e.getKey().contains(lower)) return e.getValue();
        }
        return null;
    }

    public int tierLevel(String companyName) {
        CompanyEntry e = lookup(companyName);
        if (e == null) return 1;
        return TIER_ORDER.getOrDefault(e.tier, 1);
    }

    public double credibility(String companyName) {
        CompanyEntry e = lookup(companyName);
        return e != null ? e.baseCredibility : 0.5;
    }

    public double domainBoost(String companyName, String domain) {
        CompanyEntry e = lookup(companyName);
        if (e == null) return 1.0;
        if (domain != null && e.domainBoost.containsKey(domain)) return e.domainBoost.get(domain);
        return e.domainBoost.getOrDefault("default", 1.0);
    }

    public boolean hasRecentSignal(String companyName, String signal) {
        CompanyEntry e = lookup(companyName);
        return e != null && e.recentSignals.contains(signal);
    }

    public String tierLabel(String companyName) {
        CompanyEntry e = lookup(companyName);
        return e != null ? e.tier : "UNKNOWN";
    }

    private List<String> toList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) result.add(item.asText());
        return result;
    }

    public static class CompanyEntry {
        public String key;
        public List<String> matchForms = List.of();
        public String tier;
        public double baseCredibility;
        public Map<String, Double> domainBoost = new HashMap<>();
        public String note;
        public List<String> recentSignals = List.of();

        public String getKey() { return key; }
        public String getTier() { return tier; }
        public double getBaseCredibility() { return baseCredibility; }
        public String getNote() { return note; }
        public List<String> getRecentSignals() { return recentSignals; }
    }
}
