package com.resumestudio.reviewer.ats;

import java.util.List;

/**
 * Full ATS score report returned by POST /api/ats/score.
 *
 * Mirrors the nav structure from the reference project:
 *   Overview → Breakdown → Impact (quantifying-impact, action-verb-use,
 *   accomplishments, repetition) → Brevity (length, filler-words,
 *   total-bullet-points, bullet-points-length) → Style (sections,
 *   personal-pronouns, active-voice, consistency, date-order, spell-check)
 */
public class AtsReport {

    // ── Top-level scores ──────────────────────────────────────────────────────
    public int overallScore;          // 0-100, average of impact/brevity/style
    public int impactScore;           // 0-100
    public int brevityScore;          // 0-100
    public int styleScore;            // 0-100
    public int averageBulletScore;    // 0-100

    // ── Per-section detail (one entry per nav item) ───────────────────────────
    public List<AtsSection> sections;

    // ── Summary narrative ─────────────────────────────────────────────────────
    public String summary;

    public record AtsSection(
        String id,          // matches ATSPanelSectionId on frontend
        String label,
        int score,          // 0-100
        String status,      // "good" | "fair" | "poor"
        List<String> issues,
        List<String> suggestions
    ) {}
}
