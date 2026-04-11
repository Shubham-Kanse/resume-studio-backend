package com.resumestudio.reviewer.model;

import java.time.LocalDate;
import java.util.List;

public class WorkExperience {

    private String company;
    private String companyDescriptor;      // e.g. "Series B fintech, 200 engineers"
    private String title;
    private int icLevel;                   // normalised IC1–IC6; 0 = unknown
    private LocalDate startDate;
    private LocalDate endDate;             // null = present
    private boolean isCurrent;
    private boolean isContractOrFreelance;
    private boolean isCareerBreak;
    private double durationYears;
    private List<String> bullets;
    private List<String> skillsMentioned;  // skills extracted from bullets
    private boolean datesArePartial;       // true if only year given, no month

    // ── Getters & Setters ─────────────────────────────────────

    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }

    public String getCompanyDescriptor() { return companyDescriptor; }
    public void setCompanyDescriptor(String companyDescriptor) { this.companyDescriptor = companyDescriptor; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public int getIcLevel() { return icLevel; }
    public void setIcLevel(int icLevel) { this.icLevel = icLevel; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public boolean isCurrent() { return isCurrent; }
    public void setCurrent(boolean current) { isCurrent = current; }

    public boolean isContractOrFreelance() { return isContractOrFreelance; }
    public void setContractOrFreelance(boolean contractOrFreelance) { isContractOrFreelance = contractOrFreelance; }

    public boolean isCareerBreak() { return isCareerBreak; }
    public void setCareerBreak(boolean careerBreak) { isCareerBreak = careerBreak; }

    public double getDurationYears() { return durationYears; }
    public void setDurationYears(double durationYears) { this.durationYears = durationYears; }

    public List<String> getBullets() { return bullets; }
    public void setBullets(List<String> bullets) { this.bullets = bullets; }

    public List<String> getSkillsMentioned() { return skillsMentioned; }
    public void setSkillsMentioned(List<String> skillsMentioned) { this.skillsMentioned = skillsMentioned; }

    public boolean isDatesArePartial() { return datesArePartial; }
    public void setDatesArePartial(boolean datesArePartial) { this.datesArePartial = datesArePartial; }
}
