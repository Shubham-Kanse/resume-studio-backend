package com.resumestudio.reviewer.model;

public class Education {

    private String institution;
    private String degree;
    private String field;
    private Integer startYear;        // null if not found
    private Integer graduationYear;   // null if not found
    private boolean isRelevantField;

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
}
