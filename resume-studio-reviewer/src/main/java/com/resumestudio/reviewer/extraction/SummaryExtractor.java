package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.nlp.TextNormalizer;
import com.resumestudio.reviewer.model.Resume;
import com.resumestudio.reviewer.skills.EscoSkillGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects whether a summary section exists and evaluates its quality.
 * A strong summary should state: title, YOE, and core skills — all in 2 lines.
 */
@Component
public class SummaryExtractor {

    private static final Logger log = LoggerFactory.getLogger(SummaryExtractor.class);

    private final EscoSkillGraph escoGraph;
    private final TextNormalizer textNormalizer;
    private final ResumeOntologyService resumeOntology;

    public SummaryExtractor(EscoSkillGraph escoGraph, TextNormalizer textNormalizer,
                            ResumeOntologyService resumeOntology) {
        this.escoGraph = escoGraph;
        this.textNormalizer = textNormalizer;
        this.resumeOntology = resumeOntology;
    }

    // Generic/useless summary phrases
    private static final List<Pattern> GENERIC_PHRASES = List.of(
        Pattern.compile("passionate (about|developer|engineer|professional)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(team player|hard[- ]?working|self[- ]?motivated|fast learner|quick learner)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(results[- ]driven|detail[- ]oriented|go[- ]getter|rockstar|ninja|wizard)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(dynamic|synergy|leverage|thought leader|paradigm)\\b", Pattern.CASE_INSENSITIVE)
    );

    // YOE mention in summary
    private static final Pattern YOE_IN_SUMMARY = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*\\+?\\s*years?", Pattern.CASE_INSENSITIVE);

    public void extract(String summaryText, Resume resume) {
        if (summaryText == null || summaryText.isBlank()) {
            resume.setSummaryText(null);
            return;
        }

        String cleaned = summaryText.trim()
            .replaceAll("([^\\n])\\n(?!\\n)", "$1 ")
            .replaceAll("\\n{2,}", "\n\n")
            .trim();
        resume.setSummaryText(cleaned);
    }

    /**
     * Analyses summary quality for signal computation.
     * Uses ESCO skill taxonomy for technical skill detection (SOTA).
     */
    public SummaryAnalysis analyse(String summaryText, String jdTitle) {
        SummaryAnalysis analysis = new SummaryAnalysis();

        // Use ontology minWords (15) as the minimum — filters contact lines and fragments
        int minWords = Math.max(5, resumeOntology.getMinWords("PROFESSIONAL_SUMMARY"));
        if (summaryText == null || summaryText.isBlank()
                || summaryText.trim().length() < 30
                || summaryText.trim().split("\\s+").length < minWords) {
            analysis.setPresent(false);
            return analysis;
        }

        analysis.setPresent(true);

        // Check if mentions YOE
        analysis.setMentionsYoe(YOE_IN_SUMMARY.matcher(summaryText).find());

        // Check if mentions tech skills using ESCO taxonomy (n-gram matching)
        int skillCount = extractTechnicalSkills(summaryText);
        analysis.setMentionsSkills(skillCount >= 3); // At least 3 technical skills

        // BoW overlap between summary and JD title — more robust than word-contains
        if (jdTitle != null) {
            double overlap = textNormalizer.jaccardSimilarity(summaryText, jdTitle);
            analysis.setMentionsTitle(overlap >= 0.15); // at least 15% token overlap
        }

        // Check for generic phrases
        boolean isGeneric = GENERIC_PHRASES.stream()
            .anyMatch(p -> p.matcher(summaryText).find());
        
        // Override: if summary has strong technical content (YOE + skills), ignore generic phrases
        if (isGeneric && analysis.isMentionsYoe() && analysis.isMentionsSkills()) {
            isGeneric = false;  // Technical substance overrides generic language
        }
        analysis.setGeneric(isGeneric);

        // A strong summary: present + mentions YOE + skills + not generic
        analysis.setStrong(
            analysis.isPresent()
            && analysis.isMentionsYoe()
            && analysis.isMentionsSkills()
            && !analysis.isGeneric()
        );

        return analysis;
    }

    /**
     * Extract technical skills from summary using n-gram matching against ESCO taxonomy.
     * Uses greedy longest-match to avoid counting overlaps.
     */
    private int extractTechnicalSkills(String text) {
        if (text == null || text.isBlank()) return 0;
        
        String[] words = text.toLowerCase().split("[\\s,;.()]+");
        Set<String> foundSkills = new HashSet<>();
        boolean[] consumed = new boolean[words.length];
        
        // Greedy: try longest n-grams first to avoid overlaps
        for (int n = 3; n >= 1; n--) {
            for (int i = 0; i <= words.length - n; i++) {
                // Skip if any word in this range was already matched
                boolean alreadyUsed = false;
                for (int j = i; j < i + n; j++) {
                    if (consumed[j]) {
                        alreadyUsed = true;
                        break;
                    }
                }
                if (alreadyUsed) continue;
                
                String candidate = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + n)).trim();
                if (candidate.length() < 2) continue;
                
                if (escoGraph.isKnownSkill(candidate)) {
                    foundSkills.add(candidate);
                    // Mark these words as consumed
                    for (int j = i; j < i + n; j++) {
                        consumed[j] = true;
                    }
                }
            }
        }
        
        return foundSkills.size();
    }

    public static class SummaryAnalysis {
        private boolean present;
        private boolean mentionsTitle;
        private boolean mentionsYoe;
        private boolean mentionsSkills;
        private boolean generic;
        private boolean strong;

        public boolean isPresent() { return present; }
        public void setPresent(boolean present) { this.present = present; }
        public boolean isMentionsTitle() { return mentionsTitle; }
        public void setMentionsTitle(boolean mentionsTitle) { this.mentionsTitle = mentionsTitle; }
        public boolean isMentionsYoe() { return mentionsYoe; }
        public void setMentionsYoe(boolean mentionsYoe) { this.mentionsYoe = mentionsYoe; }
        public boolean isMentionsSkills() { return mentionsSkills; }
        public void setMentionsSkills(boolean mentionsSkills) { this.mentionsSkills = mentionsSkills; }
        public boolean isGeneric() { return generic; }
        public void setGeneric(boolean generic) { this.generic = generic; }
        public boolean isStrong() { return strong; }
        public void setStrong(boolean strong) { this.strong = strong; }
    }
}
