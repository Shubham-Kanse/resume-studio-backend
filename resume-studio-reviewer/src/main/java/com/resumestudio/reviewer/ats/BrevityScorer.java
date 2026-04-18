package com.resumestudio.reviewer.ats;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

import static com.resumestudio.reviewer.ats.BulletQualityScorer.*;

/**
 * Brevity group scorer — covers:
 *   length, filler-words, total-bullet-points, bullet-points-length
 */
@Component
public class BrevityScorer {

    // Bullets containing these fillers but also a strong metric outcome get half penalty
    private static final java.util.regex.Pattern METRIC_OUTCOME = java.util.regex.Pattern.compile(
        "\\d[\\d,.]*\\s*(?:%|percent|x(?:\\s|$))|(?:\\$|£|€)\\s*\\d|\\d{1,3}(?:,\\d{3})+|" +
        "\\b(?:kpi|okr|roi|mrr|arr|nps|csat|p99)\\b",
        java.util.regex.Pattern.CASE_INSENSITIVE);

    // Word-boundary patterns prevent false positives (e.g. "assisted" matching "unassisted")
    // NOTE: "leveraged", "ensured", "utilized" removed — they are action verbs, not fillers,
    // and penalising them when followed by evidence ("Leveraged AWS to cut time by 60%") is wrong.
    private static final List<Pattern> FILLER_PATTERNS = List.of(
        "responsible for", "tasked with", "duties included", "participated in",
        "involved in", "helped with", "worked on", "was part of", "assisted with",
        "\\bassisted\\b", "\\bsupported\\b",
        "\\bvarious\\b", "\\bseveral\\b", "\\bmultiple\\b",
        "\\betc\\b", "and more"
    ).stream().map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE)).toList();

    // ── Length ────────────────────────────────────────────────────────────────

    public AtsReport.AtsSection scoreLength(String resumeText) {
        // Estimate content words by ignoring short lines (headers, dates, contact info)
        // that inflate the raw word count without adding meaningful ATS-readable content.
        int wordCount = java.util.Arrays.stream(resumeText.split("\\n"))
            .map(String::trim)
            .filter(line -> !line.isBlank() && line.split("\\s+").length > 4)
            .mapToInt(line -> line.split("\\s+").length)
            .sum();
        if (wordCount == 0) wordCount = resumeText.split("\\s+").length; // fallback
        int score;
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (wordCount < 180) {
            score = 48;
            issues.add("Resume is very short (" + wordCount + " words). ATS parsers expect more content.");
            suggestions.add("Expand experience bullets and add a professional summary.");
        } else if (wordCount < 300) {
            score = 68;
            issues.add("Resume is short (" + wordCount + " words). Aim for 450–900 words.");
            suggestions.add("Add more detail to your experience bullets.");
        } else if (wordCount <= 900) {
            score = 100;
        } else if (wordCount <= 1100) {
            score = 84;
            issues.add("Resume is slightly long (" + wordCount + " words). Consider trimming older roles.");
        } else {
            score = 54;
            issues.add("Resume is too long (" + wordCount + " words). ATS systems prefer 1–2 pages.");
            suggestions.add("Remove roles older than 10 years or condense them to 1–2 bullets.");
        }

        return section("length", "Length", score, issues, suggestions);
    }

    // ── Filler words ──────────────────────────────────────────────────────────

    public AtsReport.AtsSection scoreFillerWords(List<String> bullets) {
        List<String> issues = new ArrayList<>();
        int hitCount = 0;

        int halfPenaltyCount = 0;
        for (String bullet : bullets) {
            for (Pattern pattern : FILLER_PATTERNS) {
                java.util.regex.Matcher m = pattern.matcher(bullet);
                if (m.find()) {
                    boolean hasMetricOutcome = METRIC_OUTCOME.matcher(bullet).find();
                    if (hasMetricOutcome) {
                        halfPenaltyCount++; // evidenced filler — penalise half
                    } else {
                        hitCount++;
                        if (issues.size() < 4) issues.add("Filler phrase '" + m.group() + "': " + truncate(bullet));
                    }
                    break;
                }
            }
        }

        int penalty = hitCount * 10 + halfPenaltyCount * 5;
        int score = clamp(100 - penalty);
        List<String> suggestions = hitCount > 0
            ? List.of("Replace filler phrases with direct action verbs.",
                      "Start bullets with what you did, not your role description.")
            : List.of();
        return section("filler-words", "Filler Words", score, issues, suggestions);
    }

    // ── Total bullet points ───────────────────────────────────────────────────

    public AtsReport.AtsSection scoreTotalBulletPoints(List<String> bullets) {
        int count = bullets.size();
        int score;
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        if (count >= 10 && count <= 18) {
            score = 96;
        } else if (count >= 19 && count <= 24) {
            score = 80;
            issues.add("High bullet count (" + count + "). Consider consolidating similar points.");
        } else if (count >= 25) {
            score = clamp(68 - (count - 25) * 4);
            issues.add("Too many bullets (" + count + "). ATS and recruiters prefer focused, high-impact bullets.");
            suggestions.add("Aim for 10–18 total experience bullets across all roles.");
        } else if (count >= 5) {
            score = clamp(60 + count * 4);
            issues.add("Low bullet count (" + count + "). Add more detail to your experience.");
        } else {
            score = 40;
            issues.add("Very few bullets (" + count + "). ATS parsers need more content to evaluate.");
            suggestions.add("Add 2–4 bullets per role describing your key contributions.");
        }

        return section("total-bullet-points", "Total Bullet Points", score, issues, suggestions);
    }

    // ── Bullet points length ──────────────────────────────────────────────────

    public AtsReport.AtsSection scoreBulletPointsLength(List<String> bullets) {
        if (bullets.isEmpty()) return section("bullet-points-length", "Bullet Points Length", 50,
            List.of("No bullets found."), List.of());

        int tooShort = 0, tooLong = 0, good = 0;
        List<String> issues = new ArrayList<>();

        for (String b : bullets) {
            int words = b.split("\\s+").length;
            if (words < 8) {
                tooShort++;
                if (issues.size() < 2) issues.add("Too short (" + words + " words): " + truncate(b));
            } else if (words > 34) {
                tooLong++;
                if (issues.size() < 2) issues.add("Too long (" + words + " words): " + truncate(b));
            } else {
                good++;
            }
        }

        double goodRatio = (double) good / bullets.size();
        int score = clamp((int) (goodRatio * 100) - tooShort * 5 - tooLong * 5);
        List<String> suggestions = new ArrayList<>();
        if (tooShort > 0) suggestions.add("Expand short bullets — aim for 12–28 words with context and result.");
        if (tooLong > 0) suggestions.add("Trim long bullets — split into two or remove filler.");
        return section("bullet-points-length", "Bullet Points Length", score, issues, suggestions);
    }

    private String truncate(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "…" : s;
    }
}
