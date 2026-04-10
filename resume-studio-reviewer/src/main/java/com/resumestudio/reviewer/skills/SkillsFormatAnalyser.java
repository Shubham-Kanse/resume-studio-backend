package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.enums.SkillsFormat;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Upgrades the initial SkillsFormat guess (from SkillsSectionExtractor)
 * based on whether JD must-have skills appear early or late in the skills section.
 *
 * SkillsSectionExtractor detects structure (categorised vs flat vs prose).
 * This class adds the ordering dimension — is the relevant skill first or buried?
 *
 * Upgrade rules:
 *   CATEGORISED_UNORDERED → OPTIMAL if JD must-haves are first in their category
 *   FLAT_UNORDERED → FLAT_ORDERED if JD must-haves appear in the first 3 tokens
 */
@Component
public class SkillsFormatAnalyser {

    private final EscoSkillGraph escoGraph;

    public SkillsFormatAnalyser(EscoSkillGraph escoGraph) {
        this.escoGraph = escoGraph;
    }

    public SkillsFormat refine(SkillsFormat initial, List<Skill> resumeSkills, List<String> jdMustHaves) {
        if (jdMustHaves == null || jdMustHaves.isEmpty()) return initial;
        if (resumeSkills == null || resumeSkills.isEmpty()) return initial;

        return switch (initial) {
            case CATEGORISED_UNORDERED -> refineCategories(resumeSkills, jdMustHaves);
            case FLAT_UNORDERED -> refineFlat(resumeSkills, jdMustHaves);
            default -> initial; // PROSE, BULLET_LIST, NO_SECTION, GENERIC_ONLY, etc. — no upgrade
        };
    }

    /**
     * CATEGORISED_UNORDERED → OPTIMAL if the first skill in the relevant category
     * is a JD must-have.
     */
    private SkillsFormat refineCategories(List<Skill> skills, List<String> mustHaves) {
        // Find the category that contains the most must-haves
        String targetCategory = findPrimaryCategory(skills, mustHaves);
        if (targetCategory == null) return SkillsFormat.CATEGORISED_UNORDERED;

        // Find skills in that category
        List<Skill> inCategory = skills.stream()
            .filter(s -> targetCategory.equalsIgnoreCase(s.getCategory()))
            .toList();

        if (inCategory.isEmpty()) return SkillsFormat.CATEGORISED_UNORDERED;

        // Check if the first 1-2 skills in that category are must-haves
        int mustHaveMatches = 0;
        int checkCount = Math.min(2, inCategory.size());
        for (int i = 0; i < checkCount; i++) {
            if (isMustHave(inCategory.get(i), mustHaves)) mustHaveMatches++;
        }

        return mustHaveMatches >= 1 ? SkillsFormat.OPTIMAL : SkillsFormat.CATEGORISED_UNORDERED;
    }

    /**
     * FLAT_UNORDERED → FLAT_ORDERED if at least one JD must-have appears
     * in the first 3 positions of the flat skill list.
     */
    private SkillsFormat refineFlat(List<Skill> skills, List<String> mustHaves) {
        int checkCount = Math.min(3, skills.size());
        for (int i = 0; i < checkCount; i++) {
            if (isMustHave(skills.get(i), mustHaves)) {
                return SkillsFormat.FLAT_ORDERED;
            }
        }
        return SkillsFormat.FLAT_UNORDERED;
    }

    private String findPrimaryCategory(List<Skill> skills, List<String> mustHaves) {
        String best = null;
        int bestScore = 0;

        // Group by category, find which has most must-have matches
        List<String> categories = skills.stream()
            .map(Skill::getCategory)
            .filter(c -> c != null && !c.isBlank())
            .distinct()
            .toList();

        for (String category : categories) {
            long score = skills.stream()
                .filter(s -> category.equalsIgnoreCase(s.getCategory()))
                .filter(s -> isMustHave(s, mustHaves))
                .count();
            if (score > bestScore) {
                bestScore = (int) score;
                best = category;
            }
        }

        return best;
    }

    private boolean isMustHave(Skill skill, List<String> mustHaves) {
        String skillLower = skill.getRawName().toLowerCase().trim();
        String canonical = escoGraph.resolve(skill.getRawName()).toLowerCase();
        for (String must : mustHaves) {
            String mustLower = must.toLowerCase().trim();
            String mustCanonical = escoGraph.resolve(must).toLowerCase();
            if (skillLower.equals(mustLower) || canonical.equals(mustCanonical)) return true;
        }
        return false;
    }
}
