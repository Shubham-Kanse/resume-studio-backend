package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.enums.SkillsFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts skills from the skills section and detects presentation format.
 * Format detection is critical — it determines visibility friction.
 *
 * Also scans experience bullets for skills mentioned in context (depth signal).
 */
@Component
public class SkillsSectionExtractor {

    private static final Logger log = LoggerFactory.getLogger(SkillsSectionExtractor.class);

    // Known soft skills to detect MIXED_SOFT_HARD or GENERIC_ONLY
    private static final Set<String> SOFT_SKILLS = Set.of(
        "communication", "teamwork", "leadership", "problem solving", "problem-solving",
        "critical thinking", "time management", "adaptability", "collaboration",
        "creativity", "attention to detail", "work ethic", "interpersonal",
        "organisational", "organizational", "team player", "self-motivated",
        "proactive", "fast learner", "quick learner", "analytical"
    );

    // Proficiency/rating indicators → SELF_RATED
    private static final Pattern SELF_RATED = Pattern.compile(
        "[█▓░●○★☆]{2,}|\\b(beginner|intermediate|advanced|expert|proficient|fluent|native)\\b",
        Pattern.CASE_INSENSITIVE);

    // Version number attached to skill → flag
    private static final Pattern VERSION_NUMBER = Pattern.compile(
        "\\s+(\\d+\\.?\\d*[x]?|v\\d+)$", Pattern.CASE_INSENSITIVE);

    // Category header patterns: "Programming: Java, Python" or "Programming Languages: ..."
    private static final Pattern CATEGORY_HEADER = Pattern.compile(
        "^([A-Za-z][A-Za-z\\s/&]{2,30}):\\s*(.+)$");

    // Abbreviation patterns — skills that are commonly abbreviated
    private static final Map<String, String> KNOWN_ABBREVIATIONS = new HashMap<>();
    static {
        KNOWN_ABBREVIATIONS.put("js", "JavaScript");
        KNOWN_ABBREVIATIONS.put("ts", "TypeScript");
        KNOWN_ABBREVIATIONS.put("pg", "PostgreSQL");
        KNOWN_ABBREVIATIONS.put("k8s", "Kubernetes");
        KNOWN_ABBREVIATIONS.put("sb", "Spring Boot");
        KNOWN_ABBREVIATIONS.put("gql", "GraphQL");
        KNOWN_ABBREVIATIONS.put("tf", "Terraform");
        KNOWN_ABBREVIATIONS.put("gh", "GitHub");
        KNOWN_ABBREVIATIONS.put("aws", "Amazon Web Services");
        KNOWN_ABBREVIATIONS.put("gcp", "Google Cloud Platform");
    }

    // Stale/legacy technologies
    private static final Set<String> STALE_TECHNOLOGIES = Set.of(
        "cobol", "fortran", "visual basic 6", "vb6", "foxpro", "delphi",
        "cold fusion", "coldfusion", "struts", "ejb", "corba", "soap",
        "flash", "silverlight", "jquery mobile", "backbone.js"
    );

    public ExtractionResult extract(String skillsText, List<String> allBullets) {
        ExtractionResult result = new ExtractionResult();

        if (skillsText == null || skillsText.isBlank()) {
            result.setFormat(SkillsFormat.NO_SECTION);
            result.setSkills(new ArrayList<>());
            return result;
        }

        // ── Detect format ─────────────────────────────────────────────────
        SkillsFormat format = detectFormat(skillsText);
        result.setFormat(format);

        // ── Extract raw skill tokens ──────────────────────────────────────
        List<Skill> skills = parseSkillTokens(skillsText);

        // ── Analyse each skill ────────────────────────────────────────────
        boolean hasSoft = false, hasTech = false, hasSelfRated = false, hasStale = false;

        for (Skill skill : skills) {
            String lower = skill.getRawName().toLowerCase().trim();

            // Abbreviation check
            if (KNOWN_ABBREVIATIONS.containsKey(lower)) {
                skill.setAbbreviation(true);
                skill.setStrippedName(KNOWN_ABBREVIATIONS.get(lower));
            }

            // Version number check
            Matcher versionMatcher = VERSION_NUMBER.matcher(skill.getRawName());
            if (versionMatcher.find()) {
                skill.setHasVersionNumber(true);
                skill.setStrippedName(skill.getRawName().substring(0, versionMatcher.start()).trim());
            }

            // Soft skill check
            if (SOFT_SKILLS.contains(lower)) hasSoft = true;
            else hasTech = true;

            // Self-rated check
            if (SELF_RATED.matcher(skill.getRawName()).find()) hasSelfRated = true;

            // Stale check
            if (STALE_TECHNOLOGIES.contains(lower)) {
                hasStale = true;
                skill.setCategory("STALE");
            }

            // Mark where found
            skill.setInSkillsSection(true);
        }

        result.setHasSoftSkillsOnly(hasSoft && !hasTech);
        result.setHasMixedSoftHard(hasSoft && hasTech);
        result.setHasSelfRatedSkills(hasSelfRated);
        result.setHasStaleSkills(hasStale);

        // Override format based on content analysis
        if (hasSoft && !hasTech) result.setFormat(SkillsFormat.GENERIC_ONLY);
        if (hasSelfRated) result.setFormat(SkillsFormat.SELF_RATED);

        result.setSkills(skills);
        return result;
    }

    // ── Format detection ──────────────────────────────────────────────────────

    private SkillsFormat detectFormat(String text) {
        String[] lines = text.split("\n");
        int categorisedLines = 0;
        int bulletLines = 0;
        int totalLines = 0;
        boolean hasProseSentence = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;
            totalLines++;

            if (CATEGORY_HEADER.matcher(trimmed).matches()) categorisedLines++;
            if (trimmed.startsWith("•") || trimmed.startsWith("-") || trimmed.startsWith("*")) bulletLines++;
            if (trimmed.split("\\s+").length > 10) hasProseSentence = true; // long sentence = prose
        }

        if (hasProseSentence) return SkillsFormat.PROSE;
        if (categorisedLines >= 2) {
            // Categorised — check if JD-relevant skills are first (handled in SkillsFormatAnalyser)
            return SkillsFormat.CATEGORISED_UNORDERED; // FormatAnalyser upgrades to OPTIMAL
        }
        if (bulletLines > totalLines / 2) return SkillsFormat.BULLET_LIST;

        // Flat comma-separated — ordering assessed in SkillsFormatAnalyser
        return SkillsFormat.FLAT_UNORDERED; // FormatAnalyser upgrades to FLAT_ORDERED
    }

    // ── Token extraction ──────────────────────────────────────────────────────

    private List<Skill> parseSkillTokens(String text) {
        List<Skill> skills = new ArrayList<>();
        String[] lines = text.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) continue;

            // Category line: "Programming: Java, Python, Go"
            Matcher categoryMatcher = CATEGORY_HEADER.matcher(trimmed);
            if (categoryMatcher.matches()) {
                String category = categoryMatcher.group(1).trim();
                String skillList = categoryMatcher.group(2).trim();
                for (String token : splitSkillTokens(skillList)) {
                    Skill skill = new Skill(token.trim());
                    skill.setCategory(category);
                    skills.add(skill);
                }
                continue;
            }

            // Remove bullet prefix
            String cleaned = trimmed.replaceAll("^[•\\-*▪◦➤►→]\\s*", "");

            // Split by comma or pipe
            for (String token : splitSkillTokens(cleaned)) {
                String t = token.trim();
                if (!t.isBlank() && t.length() > 1 && t.length() < 60) {
                    skills.add(new Skill(t));
                }
            }
        }

        return skills;
    }

    private String[] splitSkillTokens(String text) {
        // Split on comma, pipe, slash, semicolon, bullet
        return text.split("[,|/;•·]+");
    }

    // ── Result container ──────────────────────────────────────────────────────

    public static class ExtractionResult {
        private List<Skill> skills;
        private SkillsFormat format;
        private boolean hasSoftSkillsOnly;
        private boolean hasMixedSoftHard;
        private boolean hasSelfRatedSkills;
        private boolean hasStaleSkills;

        public List<Skill> getSkills() { return skills; }
        public void setSkills(List<Skill> skills) { this.skills = skills; }
        public SkillsFormat getFormat() { return format; }
        public void setFormat(SkillsFormat format) { this.format = format; }
        public boolean isHasSoftSkillsOnly() { return hasSoftSkillsOnly; }
        public void setHasSoftSkillsOnly(boolean v) { this.hasSoftSkillsOnly = v; }
        public boolean isHasMixedSoftHard() { return hasMixedSoftHard; }
        public void setHasMixedSoftHard(boolean v) { this.hasMixedSoftHard = v; }
        public boolean isHasSelfRatedSkills() { return hasSelfRatedSkills; }
        public void setHasSelfRatedSkills(boolean v) { this.hasSelfRatedSkills = v; }
        public boolean isHasStaleSkills() { return hasStaleSkills; }
        public void setHasStaleSkills(boolean v) { this.hasStaleSkills = v; }
    }
}
