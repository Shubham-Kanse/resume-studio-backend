package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Determines SkillVisibility for each skill on the resume.
 *
 * Visibility levels:
 *   SURFACE  — appears in the skills section or summary (recruiter sees in 2s)
 *   MID      — appears in the last 2 job bullets but NOT in skills section
 *   BURIED   — appears only in older job bullets
 *   MISSING  — not found anywhere
 *
 * This is the single most important signal for the 10-second pass:
 * a skill that exists but isn't visible = effectively invisible.
 */
@Component
public class SkillVisibilityAnalyser {

    private static final Logger log = LoggerFactory.getLogger(SkillVisibilityAnalyser.class);

    private final EscoSkillGraph escoGraph;

    public SkillVisibilityAnalyser(EscoSkillGraph escoGraph) {
        this.escoGraph = escoGraph;
    }

    /**
     * Enriches each Skill object with its computed visibility.
     * Also tags which role index the skill most recently appeared in.
     */
    public void analyse(List<Skill> skills, List<WorkExperience> experience, String summaryText) {
        for (Skill skill : skills) {
            SkillVisibility visibility = computeVisibility(skill, experience, summaryText);
            skill.setVisibility(visibility);

            // Resolve canonical name via ESCO
            String canonical = escoGraph.resolve(skill.getRawName());
            skill.setCanonicalName(canonical);
            skill.setCategory(escoGraph.categoryOf(canonical));

            // Recency weight per AI-integration.md Layer 2b
            skill.setRecencyWeight(computeRecencyWeight(skill, experience));
        }
    }

    /**
     * Recency weight per doc:
     *   Role ending < 12 months ago → 1.0
     *   Role ending 1–3 years ago   → 0.7
     *   Role ending 3+ years ago    → 0.4
     *   Only in skills section      → 0.5
     */
    private float computeRecencyWeight(Skill skill, List<WorkExperience> experience) {
        if (skill.isInSkillsSection() && skill.getBulletOccurrences() == 0) return 0.5f;
        int roleIdx = skill.getMostRecentRoleIndex();
        if (roleIdx < 0 || experience == null || roleIdx >= experience.size()) return 0.5f;
        WorkExperience role = experience.get(roleIdx);
        java.time.LocalDate end = role.isCurrent() ? java.time.LocalDate.now() : role.getEndDate();
        if (end == null) return 0.7f;
        long monthsAgo = java.time.temporal.ChronoUnit.MONTHS.between(end, java.time.LocalDate.now());
        if (monthsAgo < 12) return 1.0f;
        if (monthsAgo < 36) return 0.7f;
        return 0.4f;
    }

    /**
     * For skills extracted from bullets (not the skills section),
     * compute their visibility based on which role they appear in.
     */
    public SkillVisibility computeVisibility(Skill skill, List<WorkExperience> experience, String summaryText) {
        String skillLower = skill.getRawName().toLowerCase().trim();
        String canonical = escoGraph.resolve(skill.getRawName()).toLowerCase();

        // ── SURFACE: in summary ───────────────────────────────────────────
        if (summaryText != null && containsSkill(summaryText, skillLower, canonical)) {
            return SkillVisibility.SURFACE;
        }

        // ── SURFACE: already flagged as in skills section ─────────────────
        if (skill.isInSkillsSection()) {
            return SkillVisibility.SURFACE;
        }

        // ── MID or BURIED: found in experience bullets ────────────────────
        if (experience == null || experience.isEmpty()) return SkillVisibility.MISSING;

        // experience is sorted most-recent-first (index 0 = most recent)
        for (int i = 0; i < experience.size(); i++) {
            WorkExperience role = experience.get(i);
            if (role.getBullets() == null) continue;

            for (String bullet : role.getBullets()) {
                if (containsSkill(bullet, skillLower, canonical)) {
                    skill.setMostRecentRoleIndex(i);
                    skill.setBulletOccurrences(skill.getBulletOccurrences() + 1);
                    // Capture the first bullet where this skill appears (provenance)
                    if (skill.getSourceBullet() == null) skill.setSourceBullet(bullet);
                    // MID = found in one of the 2 most recent roles
                    return i <= 1 ? SkillVisibility.MID : SkillVisibility.BURIED;
                }
            }
        }

        return SkillVisibility.MISSING;
    }

    private boolean containsSkill(String text, String skillLower, String canonical) {
        String textLower = text.toLowerCase();
        return matchesWithBoundary(textLower, skillLower)
            || (!canonical.equals(skillLower) && matchesWithBoundary(textLower, canonical));
    }

    /**
     * Word-boundary-aware skill matching.
     * Prevents "Java" matching inside "JavaScript", "C" inside "C#", "Go" inside "Google".
     */
    private boolean matchesWithBoundary(String text, String skill) {
        if (skill == null || skill.isBlank()) return false;
        int idx = text.indexOf(skill);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            boolean endOk = idx + skill.length() >= text.length()
                || !Character.isLetterOrDigit(text.charAt(idx + skill.length()));
            if (startOk && endOk) return true;
            idx = text.indexOf(skill, idx + 1);
        }
        return false;
    }

    /**
     * Given a JD required skill name, find its visibility across the resume.
     * Used by SkillMatchEngine after matching to determine if a found skill is surface or buried.
     */
    public SkillVisibility findVisibilityForJdSkill(String jdSkill, List<Skill> resumeSkills,
                                                     List<WorkExperience> experience,
                                                     String summaryText) {
        String jdLower = jdSkill.toLowerCase().trim();
        String jdCanonical = escoGraph.resolve(jdSkill).toLowerCase();

        // Check if it's in the skills list (already computed)
        for (Skill skill : resumeSkills) {
            String resumeLower = skill.getRawName().toLowerCase();
            String resumeCanonical = (skill.getCanonicalName() != null ? skill.getCanonicalName() : skill.getRawName()).toLowerCase();

            if (resumeLower.equals(jdLower) || resumeCanonical.equals(jdCanonical)) {
                return skill.getVisibility() != null ? skill.getVisibility() : SkillVisibility.SURFACE;
            }
        }

        // Not in skills section — check bullets directly
        if (summaryText != null && (matchesWithBoundary(summaryText.toLowerCase(), jdLower)
            || matchesWithBoundary(summaryText.toLowerCase(), jdCanonical))) {
            return SkillVisibility.SURFACE;
        }

        if (experience != null) {
            for (int i = 0; i < experience.size(); i++) {
                WorkExperience role = experience.get(i);
                if (role.getBullets() == null) continue;
                for (String bullet : role.getBullets()) {
                    String bulletLower = bullet.toLowerCase();
                    if (matchesWithBoundary(bulletLower, jdLower)
                            || matchesWithBoundary(bulletLower, jdCanonical)) {
                        return i <= 1 ? SkillVisibility.MID : SkillVisibility.BURIED;
                    }
                }
            }
        }

        return SkillVisibility.MISSING;
    }
}
