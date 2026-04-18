package com.resumestudio.reviewer.nlp;

import com.resumestudio.reviewer.skills.MindTechOntology;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * TF-IDF vectorizer for skill importance weighting.
 * IDF is derived from the MIND-tech ontology skill type — Languages and
 * generic tools are more common (lower IDF) than niche frameworks.
 */
@Component
public class TfIdfVectorizer {

    private final MindTechOntology ontology;

    public TfIdfVectorizer(MindTechOntology ontology) {
        this.ontology = ontology;
    }

    public Map<String, Double> computeTfIdf(String text, List<String> skills) {
        if (text == null || skills.isEmpty()) return Map.of();

        String lower = text.toLowerCase();
        Map<String, Double> tfidf = new HashMap<>();

        Map<String, Integer> termFreq = new HashMap<>();
        for (String skill : skills) {
            termFreq.put(skill, countOccurrences(lower, skill.toLowerCase()));
        }

        int maxFreq = termFreq.values().stream().max(Integer::compareTo).orElse(1);

        for (String skill : skills) {
            double tf = (double) termFreq.get(skill) / maxFreq;
            double idf = computeIdf(skill);
            tfidf.put(skill, tf * idf);
        }

        double maxScore = tfidf.values().stream().max(Double::compareTo).orElse(1.0);
        if (maxScore > 0) tfidf.replaceAll((k, v) -> v / maxScore);

        return tfidf;
    }

    /**
     * IDF derived from MIND ontology skill type.
     * Languages/generic tools are common (low IDF); niche frameworks are rare (high IDF).
     */
    private double computeIdf(String skill) {
        List<String> types = ontology.getSkillType(skill);
        double df;
        if (types.isEmpty()) {
            df = 0.3; // unknown — assume moderately rare
        } else {
            String primaryType = types.get(0);
            df = switch (primaryType) {
                case "Language"          -> 0.6;  // Python, Java — very common
                case "Framework"         -> 0.35; // Spring Boot, React — common
                case "Library"           -> 0.25; // moderately common
                case "Tool"              -> 0.4;  // Git, Docker — common
                case "Platform"          -> 0.3;  // AWS, GCP — moderately common
                case "Database"          -> 0.35; // SQL, MongoDB — common
                case "Methodology"       -> 0.5;  // Agile, Scrum — very common
                case "Concept"           -> 0.2;  // niche concepts
                default                  -> 0.3;
            };
        }
        return Math.log(1.0 / (df + 0.01));
    }

    private int countOccurrences(String text, String skill) {
        int count = 0, index = 0;
        while ((index = text.indexOf(skill, index)) != -1) {
            boolean validStart = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
            boolean validEnd = index + skill.length() >= text.length()
                || !Character.isLetterOrDigit(text.charAt(index + skill.length()));
            if (validStart && validEnd) count++;
            index += skill.length();
        }
        return count;
    }

    /** Skills in the first 40% of the document get higher weight (1.0), decaying to 0.6 at end. */
    public double computePositionalWeight(String text, String skill) {
        int pos = text.toLowerCase().indexOf(skill.toLowerCase());
        if (pos == -1) return 0.5;
        double position = (double) pos / text.length();
        return position <= 0.4 ? 1.0 : 1.0 - (position - 0.4) * 0.67;
    }
}
