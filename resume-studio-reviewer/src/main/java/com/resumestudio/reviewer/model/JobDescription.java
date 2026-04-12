package com.resumestudio.reviewer.model;

import com.resumestudio.reviewer.model.enums.JdClarity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Structured representation of a job description after parsing.
 * Separates must-haves from nice-to-haves, extracts seniority signals,
 * and normalises the role title for matching.
 */
public class JobDescription {

    private String rawText;
    private String roleTitle;              // extracted title e.g. "Senior Backend Engineer"
    private String normalisedTitle;        // normalised for matching e.g. "backend engineer"
    private int icLevel;                   // estimated seniority IC1–IC6

    private Double yoeMin;                 // null if not specified
    private Double yoeMax;                 // null if open-ended
    private String yoeRawStatement;        // original text e.g. "5+ years", "3-5 years"

    private List<String> mustHaveSkills = new ArrayList<>();
    private List<String> niceToHaveSkills = new ArrayList<>();
    private List<String> impliedSkills = new ArrayList<>();  // inferred from must-haves
    
    // Skill importance weights (0.0-1.0)
    private Map<String, Double> skillWeights = new HashMap<>();
    
    // Skill-specific YOE requirements
    private Map<String, Double> skillYoeRequirements = new HashMap<>();
    
    // Parse warnings/issues
    private List<String> parseWarnings = new ArrayList<>();

    private String companyCulture;         // "fast-paced startup", "enterprise" etc.
    private boolean isRemote;
    private String location;

    // parse quality
    private boolean isWellStructured;      // JD has clear must-have / nice-to-have sections
    private double parseConfidence;        // 0.0–1.0
    private JdClarity jdClarity = JdClarity.MEDIUM;
    private int jdClarityScore;
    private String trimmedText;            // stripped JD for AI prompt (~150 tokens)

    // ── Getters & Setters ────────────────────────────────────

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getRoleTitle() { return roleTitle; }
    public void setRoleTitle(String roleTitle) { this.roleTitle = roleTitle; }

    public String getNormalisedTitle() { return normalisedTitle; }
    public void setNormalisedTitle(String normalisedTitle) { this.normalisedTitle = normalisedTitle; }

    public int getIcLevel() { return icLevel; }
    public void setIcLevel(int icLevel) { this.icLevel = icLevel; }

    public Double getYoeMin() { return yoeMin; }
    public void setYoeMin(Double yoeMin) { this.yoeMin = yoeMin; }

    public Double getYoeMax() { return yoeMax; }
    public void setYoeMax(Double yoeMax) { this.yoeMax = yoeMax; }

    public String getYoeRawStatement() { return yoeRawStatement; }
    public void setYoeRawStatement(String yoeRawStatement) { this.yoeRawStatement = yoeRawStatement; }

    public List<String> getMustHaveSkills() { return mustHaveSkills; }
    public void setMustHaveSkills(List<String> mustHaveSkills) { this.mustHaveSkills = mustHaveSkills; }

    public List<String> getNiceToHaveSkills() { return niceToHaveSkills; }
    public void setNiceToHaveSkills(List<String> niceToHaveSkills) { this.niceToHaveSkills = niceToHaveSkills; }

    public List<String> getImpliedSkills() { return impliedSkills; }
    public void setImpliedSkills(List<String> impliedSkills) { this.impliedSkills = impliedSkills; }

    public Map<String, Double> getSkillWeights() { return skillWeights; }
    public void setSkillWeights(Map<String, Double> skillWeights) { this.skillWeights = skillWeights; }

    public Map<String, Double> getSkillYoeRequirements() { return skillYoeRequirements; }
    public void setSkillYoeRequirements(Map<String, Double> skillYoeRequirements) { this.skillYoeRequirements = skillYoeRequirements; }

    public List<String> getParseWarnings() { return parseWarnings; }
    public void setParseWarnings(List<String> parseWarnings) { this.parseWarnings = parseWarnings; }

    public String getCompanyCulture() { return companyCulture; }
    public void setCompanyCulture(String companyCulture) { this.companyCulture = companyCulture; }

    public boolean isRemote() { return isRemote; }
    public void setRemote(boolean remote) { isRemote = remote; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public boolean isWellStructured() { return isWellStructured; }
    public void setWellStructured(boolean wellStructured) { isWellStructured = wellStructured; }

    public double getParseConfidence() { return parseConfidence; }
    public void setParseConfidence(double parseConfidence) { this.parseConfidence = parseConfidence; }

    public JdClarity getJdClarity() { return jdClarity; }
    public void setJdClarity(JdClarity jdClarity) { this.jdClarity = jdClarity; }

    public int getJdClarityScore() { return jdClarityScore; }
    public void setJdClarityScore(int jdClarityScore) { this.jdClarityScore = jdClarityScore; }

    public String getTrimmedText() { return trimmedText; }
    public void setTrimmedText(String trimmedText) { this.trimmedText = trimmedText; }
}
