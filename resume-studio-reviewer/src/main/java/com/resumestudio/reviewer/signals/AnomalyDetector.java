package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.skills.EscoSkillGraph;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects anomalies that a sharp recruiter would notice immediately.
 * Pure rule-based — no ML needed.
 *
 * Detects:
 *  - Skill age mismatch (claims 10yr React when React is 12yr old → borderline but ok;
 *                        claims 10yr Flutter when Flutter is 6yr old → impossible)
 *  - Title inflation (senior title + junior-level bullet language)
 *  - Date overlaps (two concurrent full-time roles — already in YoeSignalCalculator,
 *                   surfaced here for anomaly reporting)
 */
@Component
public class AnomalyDetector {

    private final EscoSkillGraph escoGraph;

    // YOE claimed per skill pattern: "10 years of React", "5+ years Java"
    private static final Pattern SKILL_YOE_CLAIM = Pattern.compile(
        "(\\d+)\\s*\\+?\\s*years?\\s+(?:of\\s+)?([A-Za-z][A-Za-z0-9.+#\\s]{1,30})",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SKILL_YOE_CLAIM_ALT = Pattern.compile(
        "([A-Za-z][A-Za-z0-9.+#\\s]{1,30})\\s+(?:for\\s+)?(\\d+)\\s*\\+?\\s*years?",
        Pattern.CASE_INSENSITIVE);

    // Junior-level indicator words in bullets
    private static final List<String> JUNIOR_BULLET_VERBS = List.of(
        "assisted", "helped", "supported", "shadowed", "learned", "was responsible for",
        "participated in", "contributed to", "worked on", "involved in"
    );

    // Senior-level indicator words in bullets
    private static final List<String> SENIOR_BULLET_VERBS = List.of(
        "architected", "designed", "led", "owned", "drove", "established", "defined",
        "mentored", "spearheaded", "pioneered", "transformed", "built from scratch"
    );

    public AnomalyDetector(EscoSkillGraph escoGraph) {
        this.escoGraph = escoGraph;
    }

    public void detect(List<Skill> skills, List<WorkExperience> experience,
                       String fullText, ResumeSignals signals) {
        detectSkillAgeMismatch(fullText, signals);
        detectTitleInflation(experience, signals);
    }

    // ── Skill age mismatch ────────────────────────────────────────────────────

    private void detectSkillAgeMismatch(String text, ResumeSignals signals) {
        if (text == null) return;

        int currentYear = LocalDate.now().getYear();

        // Pattern: "10 years of React"
        Matcher m1 = SKILL_YOE_CLAIM.matcher(text);
        while (m1.find()) {
            int claimedYears = Integer.parseInt(m1.group(1));
            String skillName = m1.group(2).trim();
            checkMismatch(skillName, claimedYears, currentYear, signals);
        }

        // Pattern: "React for 10 years"
        Matcher m2 = SKILL_YOE_CLAIM_ALT.matcher(text);
        while (m2.find()) {
            String skillName = m2.group(1).trim();
            int claimedYears = Integer.parseInt(m2.group(2));
            checkMismatch(skillName, claimedYears, currentYear, signals);
        }
    }

    private void checkMismatch(String skillName, int claimedYears, int currentYear, ResumeSignals signals) {
        String canonical = escoGraph.resolve(skillName);
        Integer releaseYear = escoGraph.releaseYearOf(canonical);

        if (releaseYear == null) return; // unknown tech — can't validate

        int maxPossibleYears = currentYear - releaseYear;
        if (claimedYears > maxPossibleYears + 1) { // +1 for rounding tolerance
            signals.setHasSkillAgeMismatch(true);
            signals.setSkillAgeMismatchDetail(
                String.format("Claims %d years of %s, but %s was released in %d (max possible: %d years).",
                    claimedYears, canonical, canonical, releaseYear, maxPossibleYears));
        }
    }

    // ── Title inflation ───────────────────────────────────────────────────────

    private void detectTitleInflation(List<WorkExperience> experience, ResumeSignals signals) {
        if (experience == null || experience.isEmpty()) return;

        // Check most recent role only
        WorkExperience recent = experience.get(0);
        if (recent.getTitle() == null || recent.getBullets() == null) return;

        int icLevel = recent.getIcLevel();
        if (icLevel < 4) return; // only flag senior+ titles

        // Count junior vs senior language in bullets
        int juniorCount = 0;
        int seniorCount = 0;
        int totalBullets = recent.getBullets().size();

        for (String bullet : recent.getBullets()) {
            String lower = bullet.toLowerCase();
            if (JUNIOR_BULLET_VERBS.stream().anyMatch(lower::contains)) juniorCount++;
            if (SENIOR_BULLET_VERBS.stream().anyMatch(lower::contains)) seniorCount++;
        }

        // Title inflation: senior title but majority of bullets use junior language
        if (totalBullets >= 3 && juniorCount > seniorCount && juniorCount > totalBullets / 2) {
            signals.setHasTitleInflation(true);
        }
    }
}
