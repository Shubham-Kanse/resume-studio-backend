package com.resumestudio.reviewer.generate;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured resume data produced by the LLM content optimizer.
 * This is the intermediate representation between AI generation and DOCX rendering.
 * Every field maps directly to a section in the output .docx.
 */
public class GeneratedResumeContent {

    // ── Identity / Contact ────────────────────────────────────────────────────
    private String candidateName;      // "Jane Smith"
    private String email;
    private String phone;
    private String location;           // "San Francisco, CA" (City, State)
    private String linkedIn;           // URL or handle
    private String gitHub;             // URL or handle
    private String portfolioUrl;       // optional

    // ── Role positioning ─────────────────────────────────────────────────────
    private String targetTitle;        // JD-matched title shown under name

    // ── Core sections ────────────────────────────────────────────────────────
    private String summary;            // 100-150 word ATS-optimized professional summary
    private List<GeneratedExperience> experience = new ArrayList<>();
    private List<GeneratedSkillCategory> skills = new ArrayList<>();
    private List<GeneratedEducation> education = new ArrayList<>();
    private List<GeneratedProject> projects = new ArrayList<>();

    // ── Generation provenance ─────────────────────────────────────────────────
    // "optimized" when the LLM produced the content; "passthrough" when the LLM
    // failed and we fell back to lightly-reorganized source data without the
    // STAR-T rewrite, summary formula, or keyword densification.
    private GenerationMode generationMode = GenerationMode.OPTIMIZED;

    public GenerationMode getGenerationMode() { return generationMode; }
    public void setGenerationMode(GenerationMode generationMode) { this.generationMode = generationMode; }

    public enum GenerationMode { OPTIMIZED, PASSTHROUGH }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getLinkedIn() { return linkedIn; }
    public void setLinkedIn(String linkedIn) { this.linkedIn = linkedIn; }

    public String getGitHub() { return gitHub; }
    public void setGitHub(String gitHub) { this.gitHub = gitHub; }

    public String getPortfolioUrl() { return portfolioUrl; }
    public void setPortfolioUrl(String portfolioUrl) { this.portfolioUrl = portfolioUrl; }

    public String getTargetTitle() { return targetTitle; }
    public void setTargetTitle(String targetTitle) { this.targetTitle = targetTitle; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<GeneratedExperience> getExperience() { return experience; }
    public void setExperience(List<GeneratedExperience> experience) { this.experience = experience; }

    public List<GeneratedSkillCategory> getSkills() { return skills; }
    public void setSkills(List<GeneratedSkillCategory> skills) { this.skills = skills; }

    public List<GeneratedEducation> getEducation() { return education; }
    public void setEducation(List<GeneratedEducation> education) { this.education = education; }

    public List<GeneratedProject> getProjects() { return projects; }
    public void setProjects(List<GeneratedProject> projects) { this.projects = projects; }

    // ── Inner types ───────────────────────────────────────────────────────────

    public static class GeneratedExperience {
        private String title;
        private String company;
        private String companyDescriptor;  // "Series C fintech, 300 engineers" — shown in parens
        private String startDate;          // MM/YYYY
        private String endDate;            // MM/YYYY or "Present"
        private List<String> bullets = new ArrayList<>();  // 3-5 per role, STAR-T formatted

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getCompany() { return company; }
        public void setCompany(String company) { this.company = company; }
        public String getCompanyDescriptor() { return companyDescriptor; }
        public void setCompanyDescriptor(String companyDescriptor) { this.companyDescriptor = companyDescriptor; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }
        public List<String> getBullets() { return bullets; }
        public void setBullets(List<String> bullets) { this.bullets = bullets; }
    }

    public static class GeneratedSkillCategory {
        private String category;          // "Programming Languages", "Cloud & Infrastructure", etc.
        private List<String> items = new ArrayList<>();

        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public List<String> getItems() { return items; }
        public void setItems(List<String> items) { this.items = items; }
    }

    public static class GeneratedEducation {
        private String degree;            // "Bachelor of Science"
        private String field;             // "Computer Science"
        private String institution;       // "University of California, Berkeley"
        private String location;          // "Berkeley, CA"
        private String graduationYear;    // "05/2020" or just "2020"
        private String gpa;               // null unless 3.5+
        private List<String> honors = new ArrayList<>();  // Dean's List, etc.

        public String getDegree() { return degree; }
        public void setDegree(String degree) { this.degree = degree; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getInstitution() { return institution; }
        public void setInstitution(String institution) { this.institution = institution; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getGraduationYear() { return graduationYear; }
        public void setGraduationYear(String graduationYear) { this.graduationYear = graduationYear; }
        public String getGpa() { return gpa; }
        public void setGpa(String gpa) { this.gpa = gpa; }
        public List<String> getHonors() { return honors; }
        public void setHonors(List<String> honors) { this.honors = honors; }
    }

    public static class GeneratedProject {
        private String name;
        private List<String> technologies = new ArrayList<>();
        private String url;               // GitHub or live URL
        private String description;       // 2-3 lines: problem + approach + impact

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public List<String> getTechnologies() { return technologies; }
        public void setTechnologies(List<String> technologies) { this.technologies = technologies; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }
}
