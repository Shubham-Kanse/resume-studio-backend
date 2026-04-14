package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.AbsenceReason;
import com.resumestudio.reviewer.model.enums.SkillMatchType;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Matches a JD required skill against all skills found on the resume.
 *
 * Match strategies in order of confidence:
 *   1. EXACT         — direct match after normalisation
 *   2. SYNONYM       — resolved via ESCO graph
 *   3. ABBREVIATION  — known abbreviation expansion
 *   4. VERSION_STRIPPED — matched after removing version number
 *   5. SEMANTIC      — embedding-based cosine similarity (BGE-base, 768-dim)
 *   6. PARENT_FRAMEWORK — JD: "Spring Boot", CV has "Spring" (word boundary)
 *   7. IMPLICIT      — inferred from related skill
 *   8. MISSING       — not found by any method
 */
@Component
public class SkillMatchEngine {

    private static final Logger log = LoggerFactory.getLogger(SkillMatchEngine.class);

    private final EscoSkillGraph escoGraph;
    private final SemanticSkillMatcher semanticMatcher;

    public SkillMatchEngine(EscoSkillGraph escoGraph, SemanticSkillMatcher semanticMatcher) {
        this.escoGraph = escoGraph;
        this.semanticMatcher = semanticMatcher;
    }

    public List<SkillMatchResult> matchAll(List<String> jdSkills, List<Skill> resumeSkills, boolean isMustHave) {
        if (jdSkills == null || jdSkills.isEmpty()) return List.of();
        // Build normalised index once — O(m) — so each JD skill lookup is O(1) for strategies 1-4
        Map<String, Skill> normIndex = new java.util.HashMap<>();
        Map<String, Skill> canonicalIndex = new java.util.HashMap<>();
        if (resumeSkills != null) {
            for (Skill s : resumeSkills) {
                if (s.getRawName() == null) continue;
                normIndex.put(normalise(s.getRawName()), s);
                String resolved = escoGraph.resolve(s.getRawName());
                if (resolved != null) canonicalIndex.put(resolved.toLowerCase(), s);
            }
        }
        List<SkillMatchResult> results = new ArrayList<>();
        for (String jdSkill : jdSkills) {
            results.add(match(jdSkill, resumeSkills, isMustHave, normIndex, canonicalIndex));
        }
        return results;
    }

    public SkillMatchResult match(String jdSkill, List<Skill> resumeSkills, boolean isMustHave) {
        return match(jdSkill, resumeSkills, isMustHave, null, null);
    }

    private SkillMatchResult match(String jdSkill, List<Skill> resumeSkills, boolean isMustHave,
                                   Map<String, Skill> normIndex, Map<String, Skill> canonicalIndex) {
        if (jdSkill == null || jdSkill.isBlank()) {
            SkillMatchResult result = new SkillMatchResult(jdSkill, isMustHave);
            result.setMatchType(SkillMatchType.MISSING);
            result.setVisibility(SkillVisibility.MISSING);
            result.setCanonicalName("");
            return result;
        }

        SkillMatchResult result = new SkillMatchResult(jdSkill, isMustHave);

        String jdNormalised = normalise(jdSkill);
        String resolved = escoGraph.resolve(jdSkill);
        String jdCanonical = resolved != null ? resolved.toLowerCase() : jdNormalised;

        if (resumeSkills == null || resumeSkills.isEmpty()) {
            result.setMatchType(SkillMatchType.MISSING);
            result.setVisibility(SkillVisibility.MISSING);
            result.setCanonicalName(jdCanonical);
            return result;
        }

        // ── Strategy 1: EXACT (O(1) with index) ───────────────────────────
        if (normIndex != null) {
            Skill exact = normIndex.get(jdNormalised);
            if (exact != null) {
                log.debug("EXACT match: '{}' → '{}'", jdSkill, exact.getRawName());
                return buildResult(result, exact, SkillMatchType.EXACT, jdCanonical);
            }
            // ── Strategy 2: SYNONYM (O(1) with index) ─────────────────────
            Skill synonym = canonicalIndex != null ? canonicalIndex.get(jdCanonical) : null;
            if (synonym != null && !jdNormalised.equals(normalise(synonym.getRawName()))) {
                log.debug("SYNONYM match: '{}' → '{}' (canonical: '{}')", jdSkill, synonym.getRawName(), jdCanonical);
                return buildResult(result, synonym, SkillMatchType.SYNONYM, jdCanonical);
            }
        }

        for (Skill resumeSkill : resumeSkills) {
            String rawName = resumeSkill.getRawName();
            if (rawName == null || rawName.isBlank()) continue;

            String resumeNorm = normalise(rawName);
            String resolvedResume = escoGraph.resolve(rawName);
            String resumeCanonical = resolvedResume != null ? resolvedResume.toLowerCase() : resumeNorm;

            // Fallback strategies 1 & 2 when no index provided
            if (normIndex == null) {
                if (jdNormalised.equals(resumeNorm)) {
                    return buildResult(result, resumeSkill, SkillMatchType.EXACT, jdCanonical);
                }
                if (jdCanonical.equals(resumeCanonical) && !jdNormalised.equals(resumeNorm)) {
                    return buildResult(result, resumeSkill, SkillMatchType.SYNONYM, jdCanonical);
                }
            }

            // ── Strategy 3: ABBREVIATION ──────────────────────────────────
            if (resumeSkill.isAbbreviation()) {
                String expanded = normalise(resumeSkill.getStrippedName() != null
                    ? resumeSkill.getStrippedName() : resumeSkill.getRawName());
                if (jdNormalised.equals(expanded) || jdCanonical.equals(expanded)) {
                    log.debug("ABBREVIATION match: '{}' → '{}'", jdSkill, resumeSkill.getRawName());
                    result.setAbbreviationMismatch(true);
                    return buildResult(result, resumeSkill, SkillMatchType.ABBREVIATION, jdCanonical);
                }
            }

            // ── Strategy 4: VERSION_STRIPPED ──────────────────────────────
            if (resumeSkill.isHasVersionNumber() && resumeSkill.getStrippedName() != null) {
                String stripped = normalise(resumeSkill.getStrippedName());
                if (jdNormalised.equals(stripped) || jdCanonical.equals(stripped)) {
                    log.debug("VERSION_STRIPPED match: '{}' → '{}'", jdSkill, resumeSkill.getRawName());
                    return buildResult(result, resumeSkill, SkillMatchType.VERSION_STRIPPED, jdCanonical);
                }
            }
        }

        // ── Strategy 5: PARENT_FRAMEWORK ──────────────────────────────────
        // JD asks for "Spring Boot", CV has "Spring" (parent) — higher confidence than semantic
        for (Skill resumeSkill : resumeSkills) {
            String rawName = resumeSkill.getRawName();
            if (rawName == null || rawName.isBlank()) continue;
            String resumeNorm = normalise(rawName);
            if (resumeNorm.length() > 4 && jdNormalised.matches(".*\\b" + java.util.regex.Pattern.quote(resumeNorm) + "\\b.*")) {
                log.debug("PARENT_FRAMEWORK match: '{}' → '{}'", jdSkill, rawName);
                return buildResult(result, resumeSkill, SkillMatchType.PARENT_FRAMEWORK, jdCanonical);
            }
        }

        // ── Strategy 6: SEMANTIC (embeddings) ─────────────────────────────
        SemanticSkillMatcher.MatchResult semantic = semanticMatcher.findBestMatch(jdSkill, resumeSkills);
        if (semantic != null) {
            log.debug("SEMANTIC match: '{}' → '{}' (score: {})", jdSkill, semantic.getSkill().getRawName(), semantic.getScore());
            SkillMatchResult semanticResult = buildResult(result, semantic.getSkill(), SkillMatchType.SEMANTIC, jdCanonical);
            semanticResult.setSemanticScore((double) semantic.getScore());
            return semanticResult;
        }

        // ── Strategy 7: ESCO equivalence ──────────────────────────────────
        for (Skill resumeSkill : resumeSkills) {
            if (escoGraph.areEquivalent(jdSkill, resumeSkill.getRawName())) {
                log.debug("ESCO_EQUIVALENT match: '{}' ≈ '{}'", jdSkill, resumeSkill.getRawName());
                return buildResult(result, resumeSkill, SkillMatchType.IMPLICIT, jdCanonical);
            }
        }

        // ── Strategy 8: IMPLICIT (MIND implied graph) ─────────────────────
        for (Skill resumeSkill : resumeSkills) {
            String skillForLookup = resumeSkill.getCanonicalName() != null
                ? resumeSkill.getCanonicalName() : resumeSkill.getRawName();
            if (skillForLookup == null) continue;
            List<String> related = escoGraph.relatedSkills(skillForLookup);
            for (String rel : related) {
                if (normalise(rel).equals(jdNormalised) || normalise(rel).equals(jdCanonical)) {
                    log.debug("IMPLICIT match: '{}' implied by '{}' via '{}'", jdSkill, resumeSkill.getRawName(), rel);
                    SkillMatchResult implicit = buildResult(result, resumeSkill, SkillMatchType.IMPLICIT, jdCanonical);
                    implicit.setVisibility(SkillVisibility.MISSING);
                    return implicit;
                }
            }
        }

        // ── Strategy 9: MISSING — classify absence reason ─────────────────
        log.debug("MISSING: '{}' not found in resume", jdSkill);
        result.setMatchType(SkillMatchType.MISSING);
        result.setVisibility(SkillVisibility.MISSING);
        result.setCanonicalName(jdCanonical);
        result.setAbsenceReason(classifyAbsenceReason(jdNormalised, resumeSkills));
        return result;
    }

    private AbsenceReason classifyAbsenceReason(String jdNorm, List<Skill> resumeSkills) {
        for (Skill s : resumeSkills) {
            String rn = normalise(s.getRawName());
            if (rn.contains(jdNorm) || jdNorm.contains(rn)) return AbsenceReason.UNLABELLED;
        }
        for (Skill s : resumeSkills) {
            // OMITTED: in skills section but never appears in any bullet
            if (s.isInSkillsSection() && s.getBulletOccurrences() == 0) {
                String rn = normalise(s.getRawName());
                if (rn.equals(jdNorm)) return AbsenceReason.OMITTED;
            }
        }
        for (Skill s : resumeSkills) {
            List<String> implied = escoGraph.relatedSkills(
                s.getCanonicalName() != null ? s.getCanonicalName() : s.getRawName());
            if (implied.stream().anyMatch(i -> normalise(i).equals(jdNorm)))
                return AbsenceReason.IMPLIED;
        }
        return AbsenceReason.GENUINE_GAP;
    }

    private SkillMatchResult buildResult(SkillMatchResult result, Skill resumeSkill,
                                          SkillMatchType matchType, String canonical) {
        result.setMatchType(matchType);
        result.setResumeSkill(resumeSkill.getRawName());
        result.setCanonicalName(canonical);
        result.setVisibility(resumeSkill.getVisibility() != null ? resumeSkill.getVisibility() : SkillVisibility.SURFACE);

        // Apply recency weight: finalConfidence = matchConfidence * recencyWeight
        float matchConfidence = switch (matchType) {
            case EXACT, SYNONYM, ABBREVIATION, VERSION_STRIPPED -> 1.0f;
            case PARENT_FRAMEWORK -> 0.9f;
            case SEMANTIC -> result.getSemanticScore() != null ? result.getSemanticScore().floatValue() : 0.75f;
            case IMPLICIT -> 0.8f;
            default -> 0.5f;
        };
        float recency = resumeSkill.getRecencyWeight();
        result.setRecencyWeight(recency);
        result.setFinalConfidence(matchConfidence * recency);
        return result;
    }

    private String normalise(String s) {
        if (s == null) return "";
        return s.toLowerCase().trim()
            .replaceAll("\\s+", " ")
            .replaceAll("[^a-z0-9.#+\\s]", "");
    }
}
