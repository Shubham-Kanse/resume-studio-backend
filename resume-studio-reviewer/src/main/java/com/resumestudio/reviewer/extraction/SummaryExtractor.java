package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.Resume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Detects whether a summary section exists and evaluates its quality.
 * A strong summary should state: title, YOE, and core skills — all in 2 lines.
 */
@Component
public class SummaryExtractor {

    private static final Logger log = LoggerFactory.getLogger(SummaryExtractor.class);

    // Generic/useless summary phrases
    private static final List<Pattern> GENERIC_PHRASES = List.of(
        Pattern.compile("passionate (about|developer|engineer|professional)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(team player|hard[- ]?working|self[- ]?motivated|fast learner|quick learner)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(results[- ]driven|detail[- ]oriented|go[- ]getter|rockstar|ninja|wizard)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(dynamic|synergy|leverage|thought leader|paradigm)\\b", Pattern.CASE_INSENSITIVE)
    );

    // YOE mention in summary
    private static final Pattern YOE_IN_SUMMARY = Pattern.compile(
        "(\\d+)\\s*\\+?\\s*years?", Pattern.CASE_INSENSITIVE);

    // Technical skill mention — any word that looks like a tech term
    private static final Pattern TECH_TERM = Pattern.compile(
        "\\b(Java|Python|Go|Rust|Kotlin|TypeScript|JavaScript|C\\+\\+|C#|" +
        "Spring|React|Node|AWS|GCP|Azure|Kubernetes|Docker|SQL|PostgreSQL|" +
        "MongoDB|Redis|Kafka|Microservices|API|REST|GraphQL|Machine Learning|" +
        "DevOps|Cloud|Backend|Frontend|Full[- ]?Stack|Mobile|iOS|Android)\\b",
        Pattern.CASE_INSENSITIVE);

    public void extract(String summaryText, Resume resume) {
        if (summaryText == null || summaryText.isBlank()) {
            resume.setSummaryText(null);
            return;
        }

        String cleaned = summaryText.trim();
        resume.setSummaryText(cleaned);
    }

    /**
     * Analyses summary quality for signal computation.
     * Returns a SummaryAnalysis with quality flags.
     */
    public SummaryAnalysis analyse(String summaryText, String jdTitle) {
        SummaryAnalysis analysis = new SummaryAnalysis();

        if (summaryText == null || summaryText.isBlank()) {
            analysis.setPresent(false);
            return analysis;
        }

        analysis.setPresent(true);

        // Check if mentions YOE
        analysis.setMentionsYoe(YOE_IN_SUMMARY.matcher(summaryText).find());

        // Check if mentions tech skills
        analysis.setMentionsSkills(TECH_TERM.matcher(summaryText).find());

        // Check if mentions title/role
        if (jdTitle != null) {
            String[] titleWords = jdTitle.toLowerCase().split("\\s+");
            String summaryLower = summaryText.toLowerCase();
            int matched = 0;
            for (String word : titleWords) {
                if (word.length() > 3 && summaryLower.contains(word)) matched++;
            }
            analysis.setMentionsTitle(matched >= Math.max(1, titleWords.length / 2));
        }

        // Check for generic phrases
        boolean isGeneric = GENERIC_PHRASES.stream()
            .anyMatch(p -> p.matcher(summaryText).find());
        analysis.setGeneric(isGeneric);

        // A strong summary: present + mentions title + YOE + skills + not generic
        analysis.setStrong(
            analysis.isPresent()
            && analysis.isMentionsYoe()
            && analysis.isMentionsSkills()
            && !analysis.isGeneric()
        );

        return analysis;
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
