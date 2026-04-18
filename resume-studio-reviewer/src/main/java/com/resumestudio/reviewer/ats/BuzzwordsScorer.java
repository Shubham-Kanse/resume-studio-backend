package com.resumestudio.reviewer.ats;

import org.springframework.stereotype.Component;

import java.util.*;

import static com.resumestudio.reviewer.ats.BulletQualityScorer.*;

/**
 * Style group — Buzzwords & Clichés scorer.
 *
 * Detects overused, generic phrases that weaken credibility with ATS and
 * human recruiters. Mirrors the frontend's buzzword-families.ts logic but
 * uses the richer backend text.
 */
@Component
public class BuzzwordsScorer {

    // If a buzzword occurs in a sentence with a metric or concrete evidence, it's evidenced use
    private static final java.util.regex.Pattern EVIDENCE_SIGNAL = java.util.regex.Pattern.compile(
        "\\d[\\d,.]*\\s*(?:%|x(?:\\s|$|,)|percent)|(?:\\$|£|€)\\s*\\d|\\d{1,3}(?:,\\d{3})+|" +
        "\\b(?:team of \\d|\\d+ engineers?|\\d+ developers?|launched|shipped|delivered|" +
        "reduced|increased|improved|grew|generated|saved)\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    // Mirrors buzzword-families.ts exactly
    private static final List<BuzzwordFamily> FAMILIES = List.of(
        new BuzzwordFamily("results",   List.of("results driven","result driven","results-oriented","results oriented","proven track record")),
        new BuzzwordFamily("ownership", List.of("go-getter","go getter","self-starter","self starter","hit the ground running")),
        new BuzzwordFamily("teamwork",  List.of("team player","cross-functional","cross functional","collaborative")),
        new BuzzwordFamily("energy",    List.of("dynamic","fast-paced","fast paced","passionate","motivated")),
        new BuzzwordFamily("quality",   List.of("detail-oriented","detail oriented","hardworking","hard working"))
    );

    public AtsReport.AtsSection scoreBuzzwords(String resumeText) {
        String lower = resumeText.toLowerCase();

        List<Map<String, Object>> matchedFamilies = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        int totalHits = 0;

        int evidencedHits = 0;
        for (BuzzwordFamily family : FAMILIES) {
            List<String> found = new ArrayList<>();
            for (String term : family.terms()) {
                if (lower.contains(term)) {
                    // Find the sentence containing this term and check for evidence
                    int idx = lower.indexOf(term);
                    int sentStart = Math.max(0, lower.lastIndexOf('.', idx) + 1);
                    int sentEnd = lower.indexOf('.', idx + term.length());
                    String sentence = lower.substring(sentStart, sentEnd == -1 ? lower.length() : sentEnd);
                    boolean hasEvidence = EVIDENCE_SIGNAL.matcher(sentence).find();
                    if (hasEvidence) {
                        evidencedHits++; // evidenced — half penalty, no issue added
                    } else {
                        found.add(term);
                        totalHits++;
                    }
                }
            }
            if (!found.isEmpty()) {
                matchedFamilies.add(Map.of("family", family.name(), "terms", found, "count", found.size()));
                if (issues.size() < 4) {
                    issues.add("Cliché detected: '" + found.get(0) + "' — replace with specific evidence.");
                }
            }
        }

        int score = clamp(100 - totalHits * 14 - evidencedHits * 5);
        List<String> suggestions = totalHits > 0
            ? List.of(
                "Replace buzzwords with concrete evidence: instead of 'results-driven', show a result.",
                "Recruiters and ATS systems discount generic phrases — specificity wins.")
            : List.of();

        return section("buzzwords-cliches", "Buzzwords & Clichés", score, issues, suggestions);
    }

    /** Returns the raw matched-families data for the NLP analysis response. */
    public List<Map<String, Object>> getMatchedFamilies(String resumeText) {
        String lower = resumeText.toLowerCase();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BuzzwordFamily family : FAMILIES) {
            List<String> found = new ArrayList<>();
            for (String term : family.terms()) {
                if (lower.contains(term)) found.add(term);
            }
            if (!found.isEmpty()) {
                result.add(Map.of("family", family.name(), "terms", found, "count", found.size()));
            }
        }
        return result;
    }

    private record BuzzwordFamily(String name, List<String> terms) {}
}
