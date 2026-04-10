package com.resumestudio.reviewer.model;


import com.resumestudio.reviewer.model.enums.SkillVisibility;

/**
 * A single skill extracted from the resume.
 * Tracks where it was found and how visible it is to a 10-second scan.
 */
public class Skill {

    private String rawName;              // exactly as written on resume e.g. "PG", "SB"
    private String canonicalName;        // resolved via ESCO e.g. "PostgreSQL", "Spring Boot"
    private String category;             // ESCO category: Programming Language, Framework, etc.
    private SkillVisibility visibility;  // SURFACE, MID, BURIED, MISSING
    private boolean inSkillsSection;
    private boolean inSummary;
    private int bulletOccurrences;       // how many job bullets mention this skill
    private int mostRecentRoleIndex;     // 0 = most recent; -1 = not in any bullet
    private boolean isAbbreviation;      // "K8s", "PG", "JS"
    private boolean hasVersionNumber;    // "Java 17", "Spring Boot 3.x"
    private String strippedName;         // version stripped: "Java 17" → "Java"

    public Skill() {}

    public Skill(String rawName) {
        this.rawName = rawName;
    }

    // ── Getters & Setters ────────────────────────────────────

    public String getRawName() { return rawName; }
    public void setRawName(String rawName) { this.rawName = rawName; }

    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public SkillVisibility getVisibility() { return visibility; }
    public void setVisibility(SkillVisibility visibility) { this.visibility = visibility; }

    public boolean isInSkillsSection() { return inSkillsSection; }
    public void setInSkillsSection(boolean inSkillsSection) { this.inSkillsSection = inSkillsSection; }

    public boolean isInSummary() { return inSummary; }
    public void setInSummary(boolean inSummary) { this.inSummary = inSummary; }

    public int getBulletOccurrences() { return bulletOccurrences; }
    public void setBulletOccurrences(int bulletOccurrences) { this.bulletOccurrences = bulletOccurrences; }

    public int getMostRecentRoleIndex() { return mostRecentRoleIndex; }
    public void setMostRecentRoleIndex(int mostRecentRoleIndex) { this.mostRecentRoleIndex = mostRecentRoleIndex; }

    public boolean isAbbreviation() { return isAbbreviation; }
    public void setAbbreviation(boolean abbreviation) { isAbbreviation = abbreviation; }

    public boolean isHasVersionNumber() { return hasVersionNumber; }
    public void setHasVersionNumber(boolean hasVersionNumber) { this.hasVersionNumber = hasVersionNumber; }

    public String getStrippedName() { return strippedName; }
    public void setStrippedName(String strippedName) { this.strippedName = strippedName; }

    /** Returns best name to use for display — canonical if resolved, otherwise raw. */
    public String getDisplayName() {
        return canonicalName != null ? canonicalName : rawName;
    }
}
