package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
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
        }
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
                    // MID = found in one of the 2 most recent roles
                    return i <= 1 ? SkillVisibility.MID : SkillVisibility.BURIED;
                }
            }
        }

        return SkillVisibility.MISSING;
    }

    private boolean containsSkill(String text, String skillLower, String canonical) {
        String textLower = text.toLowerCase();
        return textLower.contains(skillLower) || textLower.contains(canonical);
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
        if (summaryText != null && (summaryText.toLowerCase().contains(jdLower)
            || summaryText.toLowerCase().contains(jdCanonical))) {
            return SkillVisibility.SURFACE;
        }

        if (experience != null) {
            for (int i = 0; i < experience.size(); i++) {
                WorkExperience role = experience.get(i);
                if (role.getBullets() == null) continue;
                for (String bullet : role.getBullets()) {
                    String bulletLower = bullet.toLowerCase();
                    if (bulletLower.contains(jdLower) || bulletLower.contains(jdCanonical)) {
                        return i <= 1 ? SkillVisibility.MID : SkillVisibility.BURIED;
                    }
                }
            }
        }

        return SkillVisibility.MISSING;
    }
}
