package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import com.resumestudio.reviewer.skills.MindTechOntology;

import java.util.*;

/**
 * OR-group-aware skill counting using the MIND-tech ontology.
 *
 * When a JD lists multiple skills of the same ontology type (e.g. three Database
 * skills: MySQL, PostgreSQL, MongoDB), the candidate satisfies the group by having
 * any one of them. This reflects how JDs actually work: "experience with relational
 * and/or NoSQL databases" means pick one, not all three.
 *
 * Groups are derived from MindTechOntology.getSkillType() — no hardcoding.
 * Types that form OR groups: Database, Service (cloud platforms), ProgrammingLanguage
 * (when multiple alternatives listed), Framework (when alternatives listed).
 *
 * Types that do NOT form OR groups (each is independently required):
 * Tool, Library, Methodology, Practice — these are additive, not alternatives.
 */
public final class SkillGroupUtils {

    private SkillGroupUtils() {}

    // Types where multiple JD entries are OR alternatives, not AND requirements
    private static final Set<String> OR_GROUP_TYPES = Set.of(
        "Database", "Service", "ProgrammingLanguage", "Framework", "Webserver"
    );

    public record GroupedCounts(long total, long missing, long buried) {
        public long found() { return total - missing; }
        public double missingRatio() { return total > 0 ? (double) missing / total : 0; }
    }

    public static GroupedCounts count(List<SkillMatchResult> results, MindTechOntology ontology) {
        if (results == null || results.isEmpty()) return new GroupedCounts(0, 0, 0);

        // Group skills by their ontology type
        // key = type (e.g. "Database"), value = list of results with that type
        Map<String, List<SkillMatchResult>> byType = new LinkedHashMap<>();
        List<SkillMatchResult> ungrouped = new ArrayList<>();

        for (SkillMatchResult r : results) {
            List<String> types = ontology.getSkillType(r.getJdSkill());
            String primaryType = types.isEmpty() ? null : types.get(0);

            if (primaryType != null && OR_GROUP_TYPES.contains(primaryType)) {
                byType.computeIfAbsent(primaryType, k -> new ArrayList<>()).add(r);
            } else {
                ungrouped.add(r);
            }
        }

        long total = 0, missing = 0, buried = 0;

        // OR groups: count as 1 requirement, satisfied if any member is found
        for (Map.Entry<String, List<SkillMatchResult>> entry : byType.entrySet()) {
            List<SkillMatchResult> group = entry.getValue();

            // If only one skill of this type in the JD, treat it as a hard requirement
            if (group.size() == 1) {
                ungrouped.add(group.get(0));
                continue;
            }

            // Multiple skills of same type → OR group
            total++;
            boolean anyFound = group.stream().anyMatch(r -> r.getVisibility() != SkillVisibility.MISSING);
            boolean anyBuried = !anyFound && group.stream().anyMatch(r -> r.getVisibility() == SkillVisibility.BURIED);
            if (!anyFound) missing++;
            else if (anyBuried) buried++;
        }

        // Individual requirements: count each separately
        for (SkillMatchResult r : ungrouped) {
            total++;
            if (r.getVisibility() == SkillVisibility.MISSING) missing++;
            else if (r.getVisibility() == SkillVisibility.BURIED) buried++;
        }

        return new GroupedCounts(total, missing, buried);
    }

    /** Overload without ontology — falls back to raw counts (used in tests) */
    public static GroupedCounts count(List<SkillMatchResult> results) {
        if (results == null || results.isEmpty()) return new GroupedCounts(0, 0, 0);
        long total = results.size();
        long missing = results.stream().filter(r -> r.getVisibility() == SkillVisibility.MISSING).count();
        long buried = results.stream().filter(r -> r.getVisibility() == SkillVisibility.BURIED).count();
        return new GroupedCounts(total, missing, buried);
    }
}
