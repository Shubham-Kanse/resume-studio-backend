package com.resumestudio.reviewer.model;

public class Education {

    private String institution;
    private String degree;
    private String field;
    private Integer startYear;        // null if not found
    private Integer graduationYear;   // null if not found or ongoing
    private boolean isRelevantField;
    private boolean currentlyStudying;
    private String institutionTier;   // ELITE, PRESTIGE, STRONG, GOOD, etc.
    private double institutionBoost;  // 0.0–1.0 credibility boost
    private double degreeRelevance;   // 0.0–1.0 relevance to tech roles

    public String getInstitution() { return institution; }
    public void setInstitution(String institution) { this.institution = institution; }
    public String getDegree() { return degree; }
    public void setDegree(String degree) { this.degree = degree; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public Integer getStartYear() { return startYear; }
    public void setStartYear(Integer startYear) { this.startYear = startYear; }
    public Integer getGraduationYear() { return graduationYear; }
    public void setGraduationYear(Integer graduationYear) { this.graduationYear = graduationYear; }
    public boolean isRelevantField() { return isRelevantField; }
    public void setRelevantField(boolean relevantField) { isRelevantField = relevantField; }
    public boolean isCurrentlyStudying() { return currentlyStudying; }
    public void setCurrentlyStudying(boolean currentlyStudying) { this.currentlyStudying = currentlyStudying; }
    public String getInstitutionTier() { return institutionTier; }
    public void setInstitutionTier(String institutionTier) { this.institutionTier = institutionTier; }
    public double getInstitutionBoost() { return institutionBoost; }
    public void setInstitutionBoost(double institutionBoost) { this.institutionBoost = institutionBoost; }
    public double getDegreeRelevance() { return degreeRelevance; }
    public void setDegreeRelevance(double degreeRelevance) { this.degreeRelevance = degreeRelevance; }
}
