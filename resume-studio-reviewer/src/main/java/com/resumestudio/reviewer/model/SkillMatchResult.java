package com.resumestudio.reviewer.model;


import com.resumestudio.reviewer.model.enums.SkillMatchType;
import com.resumestudio.reviewer.model.enums.SkillVisibility;

/**
 * Result of matching a single JD required skill against the resume.
 */
public class SkillMatchResult {

    private String jdSkill;                // skill as written in JD
    private String resumeSkill;            // skill as written on resume (null if missing)
    private String canonicalName;          // resolved canonical form
    private SkillMatchType matchType;      // how it was matched
    private SkillVisibility visibility;    // where it was found on resume
    private boolean isMustHave;
    private boolean isAbbreviationMismatch; // JD: "PostgreSQL", CV: "PG"

    public SkillMatchResult() {}

    public SkillMatchResult(String jdSkill, boolean isMustHave) {
        this.jdSkill = jdSkill;
        this.isMustHave = isMustHave;
        this.matchType = SkillMatchType.MISSING;
        this.visibility = SkillVisibility.MISSING;
    }

    public boolean isMatched() {
        return matchType != null && matchType != SkillMatchType.MISSING;
    }

    public String getJdSkill() { return jdSkill; }
    public void setJdSkill(String jdSkill) { this.jdSkill = jdSkill; }

    public String getResumeSkill() { return resumeSkill; }
    public void setResumeSkill(String resumeSkill) { this.resumeSkill = resumeSkill; }

    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }

    public SkillMatchType getMatchType() { return matchType; }
    public void setMatchType(SkillMatchType matchType) { this.matchType = matchType; }

    public SkillVisibility getVisibility() { return visibility; }
    public void setVisibility(SkillVisibility visibility) { this.visibility = visibility; }

    public boolean isMustHave() { return isMustHave; }
    public void setMustHave(boolean mustHave) { isMustHave = mustHave; }

    public boolean isAbbreviationMismatch() { return isAbbreviationMismatch; }
    public void setAbbreviationMismatch(boolean abbreviationMismatch) { isAbbreviationMismatch = abbreviationMismatch; }
}
