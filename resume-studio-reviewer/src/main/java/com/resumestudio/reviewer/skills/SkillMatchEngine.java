package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.SkillMatchType;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Matches a JD required skill against all skills found on the resume.
 *
 * Match strategies in order of confidence:
 *   1. EXACT         — direct match after normalisation
 *   2. SYNONYM       — resolved via ESCO graph
 *   3. ABBREVIATION  — known abbreviation expansion
 *   4. VERSION_STRIPPED — matched after removing version number
 *   5. PARENT_FRAMEWORK — JD: "Spring Boot", CV has "Spring"
 *   6. IMPLICIT      — inferred from related skill
 *   7. MISSING       — not found by any method
 */
@Component
public class SkillMatchEngine {

    private final EscoSkillGraph escoGraph;

    public SkillMatchEngine(EscoSkillGraph escoGraph) {
        this.escoGraph = escoGraph;
    }

    public List<SkillMatchResult> matchAll(List<String> jdSkills, List<Skill> resumeSkills, boolean isMustHave) {
        List<SkillMatchResult> results = new ArrayList<>();
        for (String jdSkill : jdSkills) {
            results.add(match(jdSkill, resumeSkills, isMustHave));
        }
        return results;
    }

    public SkillMatchResult match(String jdSkill, List<Skill> resumeSkills, boolean isMustHave) {
        SkillMatchResult result = new SkillMatchResult(jdSkill, isMustHave);

        String jdNormalised = normalise(jdSkill);
        String jdCanonical = escoGraph.resolve(jdSkill).toLowerCase();

        for (Skill resumeSkill : resumeSkills) {
            String resumeNorm = normalise(resumeSkill.getRawName());
            String resumeCanonical = escoGraph.resolve(resumeSkill.getRawName()).toLowerCase();

            // ── Strategy 1: EXACT ──────────────────────────────────────────
            if (jdNormalised.equals(resumeNorm)) {
                return buildResult(result, resumeSkill, SkillMatchType.EXACT, jdCanonical);
            }

            // ── Strategy 2: SYNONYM (via ESCO) ────────────────────────────
            if (jdCanonical.equals(resumeCanonical) && !jdCanonical.equals(jdNormalised)) {
                return buildResult(result, resumeSkill, SkillMatchType.SYNONYM, jdCanonical);
            }

            // ── Strategy 3: ABBREVIATION ──────────────────────────────────
            if (resumeSkill.isAbbreviation()) {
                String expanded = normalise(resumeSkill.getStrippedName() != null
                    ? resumeSkill.getStrippedName() : resumeSkill.getRawName());
                if (jdNormalised.equals(expanded) || jdCanonical.equals(expanded)) {
                    result.setAbbreviationMismatch(true);
                    return buildResult(result, resumeSkill, SkillMatchType.ABBREVIATION, jdCanonical);
                }
            }

            // ── Strategy 4: VERSION_STRIPPED ──────────────────────────────
            if (resumeSkill.isHasVersionNumber() && resumeSkill.getStrippedName() != null) {
                String stripped = normalise(resumeSkill.getStrippedName());
                if (jdNormalised.equals(stripped) || jdCanonical.equals(stripped)) {
                    return buildResult(result, resumeSkill, SkillMatchType.VERSION_STRIPPED, jdCanonical);
                }
            }

            // ── Strategy 5: PARENT_FRAMEWORK ──────────────────────────────
            // JD asks for "Spring Boot", CV has "Spring" (parent)
            if (jdNormalised.contains(resumeNorm) && resumeNorm.length() > 3) {
                return buildResult(result, resumeSkill, SkillMatchType.PARENT_FRAMEWORK, jdCanonical);
            }
        }

        // ── Strategy 6: IMPLICIT ──────────────────────────────────────────
        // Check if any resume skill implies the JD skill via ESCO relations
        for (Skill resumeSkill : resumeSkills) {
            List<String> related = escoGraph.relatedSkills(resumeSkill.getCanonicalName() != null
                ? resumeSkill.getCanonicalName() : resumeSkill.getRawName());
            for (String rel : related) {
                if (normalise(rel).equals(jdNormalised) || normalise(rel).equals(jdCanonical)) {
                    SkillMatchResult implicit = buildResult(result, resumeSkill, SkillMatchType.IMPLICIT, jdCanonical);
                    implicit.setVisibility(SkillVisibility.MISSING); // implicit = not surfaced
                    return implicit;
                }
            }
        }

        // ── Strategy 7: MISSING ───────────────────────────────────────────
        result.setMatchType(SkillMatchType.MISSING);
        result.setVisibility(SkillVisibility.MISSING);
        result.setCanonicalName(jdCanonical);
        return result;
    }

    private SkillMatchResult buildResult(SkillMatchResult result, Skill resumeSkill,
                                          SkillMatchType matchType, String canonical) {
        result.setMatchType(matchType);
        result.setResumeSkill(resumeSkill.getRawName());
        result.setCanonicalName(canonical);
        result.setVisibility(resumeSkill.getVisibility() != null ? resumeSkill.getVisibility() : SkillVisibility.SURFACE);
        return result;
    }

    private String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase().trim()
            .replaceAll("\\s+", " ")
            .replaceAll("[^a-z0-9.#+\\s]", "");
    }
}
