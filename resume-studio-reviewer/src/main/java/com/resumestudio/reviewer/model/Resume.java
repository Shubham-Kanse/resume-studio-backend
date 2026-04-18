package com.resumestudio.reviewer.model;


import com.resumestudio.reviewer.model.enums.ParseSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Master resume POJO — built progressively through the pipeline.
 * Every field is nullable; confidence reflects parse quality.
 */
public class Resume {

    // ── Identity ──────────────────────────────────────────────
    private String rawFilename;
    private String candidateName;
    private String currentTitle;
    private String currentCompany;
    private String companyDescriptor;     // e.g. "Series B fintech"
    private String location;
    private String email;
    private String phone;
    private String linkedInUrl;
    private String gitHubUrl;

    // ── Content blocks ────────────────────────────────────────
    private String summaryText;           // null if absent
    private List<WorkExperience> experience = new ArrayList<>();
    private List<Skill> skills = new ArrayList<>();
    private List<Education> education = new ArrayList<>();
    private List<Project> projects = new ArrayList<>();

    // ── Parse metadata ────────────────────────────────────────
    private ParseSource parseSource;
    private double parseConfidence;       // 0.0–1.0
    private int pageCount;
    private boolean hasPhoto;
    private boolean isMultiColumn;
    private double whitespaceRatio;       // 0.0–1.0; low = wall of text
    private double avgFontSize;
    private double minFontSize;
    private int distinctFontSizeCount;

    // ── Computed YOE ─────────────────────────────────────────
    private Double totalYoeYears;
    private boolean yoeExplicitInSummary;
    private String yoeRawStatement;

    // ── Bullet enrichment ─────────────────────────────────────
    private List<String> topBullets = new ArrayList<>(); // top 5 scored bullets for AI prompt
    private List<com.resumestudio.reviewer.nlp.BulletEnricher.EnrichedBullet> enrichedBullets = new ArrayList<>();

    // ── Skills metadata ───────────────────────────────────────
    private boolean hasStaleSkills;

    // ── Getters & Setters ────────────────────────────────────

    public String getRawFilename() { return rawFilename; }
    public void setRawFilename(String rawFilename) { this.rawFilename = rawFilename; }

    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }

    public String getCurrentTitle() { return currentTitle; }
    public void setCurrentTitle(String currentTitle) { this.currentTitle = currentTitle; }

    public String getCurrentCompany() { return currentCompany; }
    public void setCurrentCompany(String currentCompany) { this.currentCompany = currentCompany; }

    public String getCompanyDescriptor() { return companyDescriptor; }
    public void setCompanyDescriptor(String companyDescriptor) { this.companyDescriptor = companyDescriptor; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getLinkedInUrl() { return linkedInUrl; }
    public void setLinkedInUrl(String linkedInUrl) { this.linkedInUrl = linkedInUrl; }

    public String getGitHubUrl() { return gitHubUrl; }
    public void setGitHubUrl(String gitHubUrl) { this.gitHubUrl = gitHubUrl; }

    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }

    public List<WorkExperience> getExperience() { return experience; }
    public void setExperience(List<WorkExperience> experience) { this.experience = experience; }

    public List<Skill> getSkills() { return skills; }
    public void setSkills(List<Skill> skills) { this.skills = skills; }

    public List<Education> getEducation() { return education; }
    public void setEducation(List<Education> education) { this.education = education; }

    public List<Project> getProjects() { return projects; }
    public void setProjects(List<Project> projects) { this.projects = projects; }

    public ParseSource getParseSource() { return parseSource; }
    public void setParseSource(ParseSource parseSource) { this.parseSource = parseSource; }

    public double getParseConfidence() { return parseConfidence; }
    public void setParseConfidence(double parseConfidence) { this.parseConfidence = parseConfidence; }

    public int getPageCount() { return pageCount; }
    public void setPageCount(int pageCount) { this.pageCount = pageCount; }

    public boolean isHasPhoto() { return hasPhoto; }
    public void setHasPhoto(boolean hasPhoto) { this.hasPhoto = hasPhoto; }

    public boolean isMultiColumn() { return isMultiColumn; }
    public void setMultiColumn(boolean multiColumn) { isMultiColumn = multiColumn; }

    public double getWhitespaceRatio() { return whitespaceRatio; }
    public void setWhitespaceRatio(double whitespaceRatio) { this.whitespaceRatio = whitespaceRatio; }

    public double getAvgFontSize() { return avgFontSize; }
    public void setAvgFontSize(double avgFontSize) { this.avgFontSize = avgFontSize; }

    public double getMinFontSize() { return minFontSize; }
    public void setMinFontSize(double minFontSize) { this.minFontSize = minFontSize; }

    public int getDistinctFontSizeCount() { return distinctFontSizeCount; }
    public void setDistinctFontSizeCount(int distinctFontSizeCount) { this.distinctFontSizeCount = distinctFontSizeCount; }

    public Double getTotalYoeYears() { return totalYoeYears; }
    public void setTotalYoeYears(Double totalYoeYears) { this.totalYoeYears = totalYoeYears; }

    public boolean isYoeExplicitInSummary() { return yoeExplicitInSummary; }
    public void setYoeExplicitInSummary(boolean yoeExplicitInSummary) { this.yoeExplicitInSummary = yoeExplicitInSummary; }

    public String getYoeRawStatement() { return yoeRawStatement; }
    public void setYoeRawStatement(String yoeRawStatement) { this.yoeRawStatement = yoeRawStatement; }

    public List<String> getTopBullets() { return topBullets; }
    public void setTopBullets(List<String> topBullets) { this.topBullets = topBullets; }

    public List<com.resumestudio.reviewer.nlp.BulletEnricher.EnrichedBullet> getEnrichedBullets() { return enrichedBullets; }
    public void setEnrichedBullets(List<com.resumestudio.reviewer.nlp.BulletEnricher.EnrichedBullet> enrichedBullets) { this.enrichedBullets = enrichedBullets; }

    public boolean isHasStaleSkills() { return hasStaleSkills; }
    public void setHasStaleSkills(boolean hasStaleSkills) { this.hasStaleSkills = hasStaleSkills; }
}