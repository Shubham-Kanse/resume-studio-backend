package com.resumestudio.reviewer.nlp;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TF-IDF (Term Frequency-Inverse Document Frequency) vectorizer for skill importance weighting.
 * 
 * Computes:
 * - TF: How often a skill appears in the JD
 * - IDF: How rare the skill is across all JDs (estimated from corpus)
 * - TF-IDF: TF * IDF = importance score
 */
@Component
public class TfIdfVectorizer {

    // Estimated document frequencies from a corpus of tech JDs
    // Higher DF = more common skill (lower importance)
    private static final Map<String, Double> SKILL_DOCUMENT_FREQUENCIES = new HashMap<>();
    
    static {
        // Very common skills (appear in 70%+ of JDs)
        SKILL_DOCUMENT_FREQUENCIES.put("git", 0.8);
        SKILL_DOCUMENT_FREQUENCIES.put("agile", 0.75);
        SKILL_DOCUMENT_FREQUENCIES.put("scrum", 0.7);
        SKILL_DOCUMENT_FREQUENCIES.put("jira", 0.65);
        SKILL_DOCUMENT_FREQUENCIES.put("communication", 0.9);
        
        // Common skills (appear in 40-70% of JDs)
        SKILL_DOCUMENT_FREQUENCIES.put("java", 0.5);
        SKILL_DOCUMENT_FREQUENCIES.put("python", 0.5);
        SKILL_DOCUMENT_FREQUENCIES.put("javascript", 0.5);
        SKILL_DOCUMENT_FREQUENCIES.put("sql", 0.6);
        SKILL_DOCUMENT_FREQUENCIES.put("docker", 0.45);
        SKILL_DOCUMENT_FREQUENCIES.put("kubernetes", 0.4);
        SKILL_DOCUMENT_FREQUENCIES.put("aws", 0.55);
        SKILL_DOCUMENT_FREQUENCIES.put("react", 0.45);
        SKILL_DOCUMENT_FREQUENCIES.put("spring boot", 0.35);
        
        // Specialized skills (appear in 10-40% of JDs)
        SKILL_DOCUMENT_FREQUENCIES.put("golang", 0.25);
        SKILL_DOCUMENT_FREQUENCIES.put("rust", 0.15);
        SKILL_DOCUMENT_FREQUENCIES.put("kafka", 0.3);
        SKILL_DOCUMENT_FREQUENCIES.put("elasticsearch", 0.25);
        SKILL_DOCUMENT_FREQUENCIES.put("terraform", 0.3);
        SKILL_DOCUMENT_FREQUENCIES.put("graphql", 0.2);
        
        // Rare/niche skills (appear in <10% of JDs)
        SKILL_DOCUMENT_FREQUENCIES.put("elixir", 0.05);
        SKILL_DOCUMENT_FREQUENCIES.put("haskell", 0.03);
        SKILL_DOCUMENT_FREQUENCIES.put("webassembly", 0.08);
    }

    /**
     * Compute TF-IDF scores for all skills in a document.
     * 
     * @param text The JD text
     * @param skills List of extracted skills
     * @return Map of skill -> TF-IDF score (0.0-1.0)
     */
    public Map<String, Double> computeTfIdf(String text, List<String> skills) {
        if (text == null || skills.isEmpty()) {
            return Map.of();
        }
        
        String lower = text.toLowerCase();
        Map<String, Double> tfidf = new HashMap<>();
        
        // Compute term frequencies
        Map<String, Integer> termFreq = new HashMap<>();
        for (String skill : skills) {
            String skillLower = skill.toLowerCase();
            int count = countOccurrences(lower, skillLower);
            termFreq.put(skill, count);
        }
        
        // Find max frequency for normalization
        int maxFreq = termFreq.values().stream().max(Integer::compareTo).orElse(1);
        
        // Compute TF-IDF
        for (String skill : skills) {
            double tf = (double) termFreq.get(skill) / maxFreq;
            double idf = computeIdf(skill);
            double score = tf * idf;
            
            tfidf.put(skill, score);
        }
        
        // Normalize to 0-1 range
        double maxScore = tfidf.values().stream().max(Double::compareTo).orElse(1.0);
        if (maxScore > 0) {
            for (String skill : tfidf.keySet()) {
                tfidf.put(skill, tfidf.get(skill) / maxScore);
            }
        }
        
        return tfidf;
    }

    /**
     * Compute IDF (Inverse Document Frequency) for a skill.
     * IDF = log(N / df) where N = total docs, df = docs containing term
     */
    private double computeIdf(String skill) {
        String skillLower = skill.toLowerCase();
        
        // Use pre-computed DF if available
        Double df = SKILL_DOCUMENT_FREQUENCIES.get(skillLower);
        if (df == null) {
            // Default: assume moderately rare skill (30% of docs)
            df = 0.3;
        }
        
        // IDF = log(1 / df)
        // Add smoothing to avoid log(0)
        return Math.log(1.0 / (df + 0.01));
    }

    /**
     * Count occurrences of a skill in text (case-insensitive, word boundary aware).
     */
    private int countOccurrences(String text, String skill) {
        int count = 0;
        int index = 0;
        
        while ((index = text.indexOf(skill, index)) != -1) {
            // Check word boundaries
            boolean validStart = index == 0 || !Character.isLetterOrDigit(text.charAt(index - 1));
            boolean validEnd = index + skill.length() >= text.length() 
                || !Character.isLetterOrDigit(text.charAt(index + skill.length()));
            
            if (validStart && validEnd) {
                count++;
            }
            index += skill.length();
        }
        
        return count;
    }

    /**
     * Compute positional weight for a skill based on where it appears in the text.
     * Skills in the first 40% of the document get higher weight.
     */
    public double computePositionalWeight(String text, String skill) {
        String lower = text.toLowerCase();
        String skillLower = skill.toLowerCase();
        
        int firstOccurrence = lower.indexOf(skillLower);
        if (firstOccurrence == -1) {
            return 0.5; // Default if not found
        }
        
        double position = (double) firstOccurrence / text.length();
        
        // Skills in first 40% get weight 1.0, linearly decay to 0.6 at end
        if (position <= 0.4) {
            return 1.0;
        } else {
            return 1.0 - (position - 0.4) * 0.67; // Decay from 1.0 to 0.6
        }
    }

    /**
     * Update document frequency for a skill (for online learning).
     */
    public void updateDocumentFrequency(String skill, double df) {
        SKILL_DOCUMENT_FREQUENCIES.put(skill.toLowerCase(), df);
    }
}
