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
        int impact = 0, weak = 0;
        for (String bullet : bullets) {
            String first = firstVerb(bullet);
            if (first == null) continue;
            if (IMPACT_VERBS.contains(first)) impact++;
            else if (WEAK_VERBS.contains(first)) weak++;
        }
        int total = impact + weak;
        return total == 0 ? 0.5 : (double) impact / total;
    }

    public double metricDensity(List<String> bullets) {
        if (bullets == null || bullets.isEmpty()) return 0.0;
        long withMetric = bullets.stream()
            .filter(b -> b.matches(".*\\d+.*[%$xX].*|.*\\d{2,}.*|.*\\$\\d+.*"))
            .count();
        return (double) withMetric / bullets.size();
    }

    public boolean isAvailable() { return available; }

    private String firstVerb(String bullet) {
        if (bullet == null || bullet.isBlank()) return null;
        String[] words = bullet.trim().split("\\s+");
        if (words.length == 0) return null;
        String first = words[0].toLowerCase().replaceAll("[^a-z]", "");
        if (IMPACT_VERBS.contains(first) || WEAK_VERBS.contains(first)) return first;
        if (available) {
            String[] tokens = tokenize(bullet);
            String[] tags = posTag(tokens);
            for (int i = 0; i < Math.min(3, tags.length); i++) {
                if (tags[i].startsWith("VB")) return tokens[i].toLowerCase();
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
}
