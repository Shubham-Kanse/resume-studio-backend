package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import com.resumestudio.reviewer.nlp.VerbQualityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * NLU layer: cross-references what a candidate *claims* to know against
 * what their bullets actually *demonstrate*.
 *
 * Uses:
 *   - MIND ontology: impliesKnowingSkills, solvesApplicationTasks
 *   - VerbQualityService: action verb quality per bullet
 *   - Bullet text: metric density, scope signals, outcome language
 *
 * Produces per-skill credibility scores (0.0–1.0) and a resume-level
 * credibility signal that feeds into ClassificationEngine.
 *
 * Key insight: "Python" in a skills section with zero Python-related bullets
 * is a credibility gap. "Python" with bullets showing ML pipelines, data
 * processing, and quantified outcomes is high-credibility evidence.
 */
@Component
public class SkillCredibilityAnalyser {

    private static final Logger log = LoggerFactory.getLogger(SkillCredibilityAnalyser.class);

    private final MindTechOntology ontology;
    private final VerbQualityService verbQuality;

    // Patterns that indicate a skill was used meaningfully (not just listed)
    private static final Pattern METRIC_PATTERN = Pattern.compile(
        "\\d+\\s*[%$xX]|\\d{2,}|\\$\\d+|millions?|billions?|thousands?",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern OUTCOME_PATTERN = Pattern.compile(
        "\\b(reduced|increased|improved|accelerated|eliminated|saved|generated|" +
        "delivered|launched|scaled|optimized|automated|migrated|built|designed|" +
        "architected|led|owned|drove|shipped|deployed|integrated)\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern SCOPE_PATTERN = Pattern.compile(
        "\\b(team|users?|customers?|services?|systems?|platform|pipeline|" +
        "infrastructure|production|enterprise|million|thousand|daily|weekly)\\b",
        Pattern.CASE_INSENSITIVE);

    public SkillCredibilityAnalyser(MindTechOntology ontology, VerbQualityService verbQuality) {
        this.ontology = ontology;
        this.verbQuality = verbQuality;
    }

    /**
     * Analyses skill credibility across the resume and sets signals.
     *
     * Sets:
     *   - signals.skillCredibilityScore (0.0–1.0): overall evidence quality
     *   - signals.hasUnevidencedSkills: true if claimed skills lack bullet evidence
     *   - signals.impliedSkillsFound: skills implied by ontology but not explicitly listed
     */
    public void analyse(List<Skill> skills, List<WorkExperience> experience,
                        List<SkillMatchResult> mustHaveResults, ResumeSignals signals) {
        if (skills == null || skills.isEmpty()) return;

        // Build a map of all bullet text for fast lookup
        List<String> allBullets = experience == null ? List.of() :
            experience.stream()
                .filter(e -> e.getBullets() != null)
                .flatMap(e -> e.getBullets().stream())
                .filter(b -> b != null && !b.isBlank())
                .collect(Collectors.toList());

        String allBulletsText = String.join(" ", allBullets).toLowerCase();

        // ── 1. Per-skill credibility ──────────────────────────────────────
        int evidencedCount = 0;
        int unevidencedCount = 0;
        double totalCredibility = 0.0;

        for (Skill skill : skills) {
            double credibility = computeSkillCredibility(skill, allBullets, allBulletsText);
            skill.setCredibilityScore(credibility);

            if (credibility >= 0.4) evidencedCount++;
            else unevidencedCount++;
            totalCredibility += credibility;
        }

        double avgCredibility = skills.isEmpty() ? 0.5 : totalCredibility / skills.size();
        signals.setSkillCredibilityScore(avgCredibility);
        signals.setHasUnevidencedSkills(unevidencedCount > evidencedCount / 2);

        // ── 2. Implied skills from ontology ───────────────────────────────
        // If candidate has "Spring Boot", they implicitly know "Java" even if not listed
        Set<String> explicitSkillNames = skills.stream()
            .map(s -> s.getRawName().toLowerCase())
            .collect(Collectors.toSet());

        List<String> impliedFound = new ArrayList<>();
        for (Skill skill : skills) {
            List<String> implied = ontology.getImpliedSkills(skill.getRawName());
            for (String imp : implied) {
                String impLower = imp.toLowerCase();
                if (!explicitSkillNames.contains(impLower) && allBulletsText.contains(impLower)) {
                    impliedFound.add(imp);
                }
            }
        }
        signals.setImpliedSkillsFound(impliedFound.stream().distinct().limit(5).collect(Collectors.toList()));

        // ── 3. Task-based semantic alignment ─────────────────────────────
        // Check if bullet language aligns with what the JD's required skills solve
        if (mustHaveResults != null) {
            int taskAligned = 0;
            for (SkillMatchResult result : mustHaveResults) {
                if (result.getVisibility() == SkillVisibility.MISSING) continue;
                List<String> tasks = ontology.getSkillType(result.getJdSkill()); // reuse type as proxy
                // Check if any bullet mentions the skill's application domain
                String skillLower = result.getJdSkill().toLowerCase();
                boolean bulletEvidence = allBullets.stream()
                    .anyMatch(b -> b.toLowerCase().contains(skillLower) && OUTCOME_PATTERN.matcher(b).find());
                if (bulletEvidence) taskAligned++;
            }
            double taskAlignmentRatio = mustHaveResults.isEmpty() ? 0.5 :
                (double) taskAligned / mustHaveResults.stream()
                    .filter(r -> r.getVisibility() != SkillVisibility.MISSING).count();
            signals.setTaskAlignmentScore(Math.min(1.0, taskAlignmentRatio));
        }

        log.debug("SkillCredibilityAnalyser: avg={:.2f}, evidenced={}, unevidenced={}, implied={}",
            avgCredibility, evidencedCount, unevidencedCount, impliedFound.size());
    }

    private double computeSkillCredibility(Skill skill, List<String> bullets, String allBulletsText) {
        String skillLower = skill.getRawName().toLowerCase();

        // Skills only in skills section with no bullet evidence = low credibility
        if (skill.isInSkillsSection() && skill.getBulletOccurrences() == 0) {
            // Check if implied by other skills (e.g., Java implied by Spring Boot)
            boolean isImplied = bullets.stream()
                .anyMatch(b -> b.toLowerCase().contains(skillLower));
            return isImplied ? 0.5 : 0.25;
        }

        // Not found anywhere = 0
        if (skill.getVisibility() == SkillVisibility.MISSING) return 0.0;

        // Found in bullets — score based on evidence quality
        List<String> evidenceBullets = bullets.stream()
            .filter(b -> b.toLowerCase().contains(skillLower))
            .collect(Collectors.toList());

        if (evidenceBullets.isEmpty()) return 0.3;

        double score = 0.0;
        for (String bullet : evidenceBullets) {
            double bulletScore = scoreBulletEvidence(bullet);
            score = Math.max(score, bulletScore); // take the best bullet
        }

        // Recency bonus: SURFACE > MID > BURIED
        double recencyMultiplier = switch (skill.getVisibility()) {
            case SURFACE -> 1.0;
            case MID -> 0.85;
            case BURIED -> 0.6;
            default -> 0.5;
        };

        return Math.min(1.0, score * recencyMultiplier);
    }

    private double scoreBulletEvidence(String bullet) {
        double score = 0.3; // base: skill mentioned in bullet

        // Outcome language
        if (OUTCOME_PATTERN.matcher(bullet).find()) score += 0.2;

        // Metric present
        if (METRIC_PATTERN.matcher(bullet).find()) score += 0.25;

        // Scope signal
        if (SCOPE_PATTERN.matcher(bullet).find()) score += 0.1;

        // Strong action verb
        String firstWord = bullet.trim().split("\\s+")[0].toLowerCase();
        if (verbQuality.isImpactVerb(firstWord)) score += 0.15;

        return Math.min(1.0, score);
    }
}
