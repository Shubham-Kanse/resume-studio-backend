package com.resumestudio.reviewer.nlp;

import jakarta.annotation.PostConstruct;
import opennlp.tools.namefind.NameFinderME;
import opennlp.tools.namefind.TokenNameFinderModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
public class NlpService {

    private static final Logger log = LoggerFactory.getLogger(NlpService.class);

    private static final Set<String> IMPACT_VERBS = Set.of(
        "built", "designed", "architected", "led", "launched", "delivered",
        "reduced", "increased", "improved", "optimised", "optimized", "automated",
        "migrated", "refactored", "implemented", "developed", "created", "deployed",
        "scaled", "secured", "integrated", "engineered", "established", "drove",
        "accelerated", "streamlined", "eliminated", "introduced", "owned", "shipped",
        "mentored", "managed", "coordinated", "negotiated", "resolved", "diagnosed"
    );

    private static final Set<String> WEAK_VERBS = Set.of(
        "responsible", "helped", "assisted", "supported", "worked", "involved",
        "participated", "contributed", "collaborated", "utilized", "utilised",
        "leveraged", "ensured", "maintained", "handled", "performed", "conducted"
    );

    private TokenizerME tokenizer;
    private SentenceDetectorME sentenceDetector;
    private POSTaggerME posTagger;
    private NameFinderME personFinder;
    private NameFinderME orgFinder;
    private boolean available = false;

    private final TextNormalizer textNormalizer;
    private final VerbQualityService verbQuality;

    public NlpService(TextNormalizer textNormalizer, VerbQualityService verbQuality) {
        this.textNormalizer = textNormalizer;
        this.verbQuality = verbQuality;
    }

    @PostConstruct
    public void init() {
        try {
            tokenizer = new TokenizerME(load("nlp/en-token.bin", TokenizerModel.class));
            sentenceDetector = new SentenceDetectorME(load("nlp/en-sent.bin", SentenceModel.class));
            posTagger = new POSTaggerME(load("nlp/en-pos-maxent.bin", POSModel.class));
            personFinder = new NameFinderME(load("nlp/en-ner-person.bin", TokenNameFinderModel.class));
            orgFinder = new NameFinderME(load("nlp/en-ner-organization.bin", TokenNameFinderModel.class));
            available = true;
            log.info("NlpService: OpenNLP models loaded (tokenizer, POS, NER person+org)");
        } catch (Exception e) {
            log.warn("NlpService: OpenNLP unavailable ({}), falling back to rules", e.getMessage());
        }
    }

    public String[] tokenize(String text) {
        if (!available || text == null) return text != null ? text.split("\\s+") : new String[0];
        return tokenizer.tokenize(text);
    }

    public String[] sentences(String text) {
        if (!available || text == null) return new String[]{text};
        return sentenceDetector.sentDetect(text);
    }

    public String[] posTag(String[] tokens) {
        if (!available || tokens == null) return new String[0];
        return posTagger.tag(tokens);
    }

    public synchronized List<String> findPersons(String text) {
        if (!available || text == null) return List.of();
        String[] tokens = tokenize(text);
        Span[] spans = personFinder.find(tokens);
        personFinder.clearAdaptiveData();
        return spansToStrings(tokens, spans);
    }

    public synchronized List<String> findOrganizations(String text) {
        if (!available || text == null) return List.of();
        String[] tokens = tokenize(text);
        Span[] spans = orgFinder.find(tokens);
        orgFinder.clearAdaptiveData();
        return spansToStrings(tokens, spans);
    }

    public double impactVerbRatio(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) return 0.0;
        int impact = 0;
        int total = 0;
        for (String bullet : bullets) {
            if (bullet == null || bullet.isBlank()) continue;
            total++;
            String first = firstVerb(bullet);
            if (first == null) continue;
            // Ontology-first, then hardcoded fallback
            if (verbQuality.isImpactVerb(first) || IMPACT_VERBS.contains(first)) impact++;
        }
        // Denominator is ALL bullets, not just those with recognized verbs
        return total == 0 ? 0.0 : (double) impact / total;
    }

    private static final java.util.regex.Pattern METRIC_PATTERN = java.util.regex.Pattern.compile(
        "\\d+\\s*[%$xX]" +           // 40%, $1M, 3x
        "|\\$\\d+" +                  // $500K
        "|\\d{2,}" +                  // any 2+ digit number
        "|\\d+[KkMmBb]\\b" +          // 5M, 200K
        "|\\b(million|billion|thousand|hundred)s?\\b" + // scale words
        "|\\b(daily|monthly|weekly|annually|per (day|week|month|year))\\b", // frequency
        java.util.regex.Pattern.CASE_INSENSITIVE);

    public double metricDensity(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) return 0.0;
        long withMetric = bullets.stream()
            .filter(b -> b != null && METRIC_PATTERN.matcher(b).find())
            .count();
        return (double) withMetric / bullets.size();
    }

    public boolean isAvailable() { return available; }

    private String firstVerb(String bullet) {
        if (bullet == null || bullet.isBlank()) return null;
        String[] words = bullet.trim().split("\\s+");
        if (words.length == 0) return null;
        String first = words[0].toLowerCase().replaceAll("[^a-z]", "");
        String lemma = textNormalizer.lemmatize(first);
        // Ontology-first: check VerbQualityService
        if (verbQuality.lookup(lemma) != null) return lemma;
        if (verbQuality.lookup(first) != null) return first;
        // Fallback to hardcoded sets
        if (IMPACT_VERBS.contains(lemma) || IMPACT_VERBS.contains(first)) return lemma;
        if (WEAK_VERBS.contains(lemma) || WEAK_VERBS.contains(first)) return lemma;
        if (available) {
            String[] tokens = tokenize(bullet);
            String[] tags = posTag(tokens);
            for (int i = 0; i < Math.min(3, tags.length); i++) {
                if (tags[i].startsWith("VB")) return textNormalizer.lemmatize(tokens[i].toLowerCase());
            }
        }
        return null;
    }

    private List<String> spansToStrings(String[] tokens, Span[] spans) {
        List<String> result = new ArrayList<>();
        for (Span span : spans) {
            StringBuilder sb = new StringBuilder();
            for (int i = span.getStart(); i < span.getEnd(); i++) {
                if (i > span.getStart()) sb.append(" ");
                sb.append(tokens[i]);
            }
            result.add(sb.toString());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T> T load(String resource, Class<T> modelClass) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IllegalStateException("Model not found: " + resource);
            return modelClass.getConstructor(InputStream.class).newInstance(is);
        }
    }

    /** Delegates to VerbQualityService — single source of truth for verb quality. */
    public String getVerbQuality(String word) {
        if (word == null || word.isBlank()) return null;
        return verbQuality.quality(textNormalizer.lemmatize(word.toLowerCase().trim()));
    }

    /**
     * Infers demonstrated seniority level (1–6) from bullet verbs via VerbQualityService.impliedSeniority.
     * Returns 0 if insufficient data.
     */
    public int inferDemonstratedSeniority(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) return 0;

        Map<String, Integer> seniorityLevels = Map.of(
            "Intern", 1, "Junior", 2, "Mid", 3, "Senior", 4, "Lead", 4, "Staff", 5, "Principal", 6
        );

        Map<Integer, Integer> levelCounts = new java.util.HashMap<>();
        for (String bullet : bullets) {
            String firstWord = bullet.trim().split("\\s+")[0];
            List<String> implied = verbQuality.impliedSeniority(firstWord);
            for (String seniority : implied) {
                int level = seniorityLevels.getOrDefault(seniority, 0);
                if (level > 0) levelCounts.merge(level, 1, Integer::sum);
            }
        }

        if (levelCounts.isEmpty()) return 0;

        double weightedSum = 0, totalWeight = 0;
        for (Map.Entry<Integer, Integer> e : levelCounts.entrySet()) {
            double weight = e.getKey() * e.getValue();
            weightedSum += e.getKey() * weight;
            totalWeight += weight;
        }
        return totalWeight > 0 ? (int) Math.round(weightedSum / totalWeight) : 0;
    }
}
