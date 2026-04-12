package com.resumestudio.reviewer.model;

import com.resumestudio.reviewer.model.enums.*;

import java.util.ArrayList;
import java.util.List;

/**
 * All computed signals from every pipeline layer, bundled for the
 * classification engine and feedback generator.
 */
public class ResumeSignals {

    // ── Filename signals ──────────────────────────────────────
    private boolean filenameProfessional;
    private boolean filenameHasVersioning;     // v2, final, copy, new
    private boolean filenameHasName;
    private boolean filenameTooLong;           // > 40 chars
    private boolean filenameGeneric;           // resume.pdf, cv.pdf
    private String filenameIssueDetail;        // human-readable description

    // ── Format signals ────────────────────────────────────────
    private boolean formatWallOfText;          // whitespaceRatio < 0.15
    private boolean formatTooManyPages;        // > 2 pages for < 8 YOE
    private boolean formatFontTooSmall;        // minFontSize < 9pt
    private boolean formatHasPhoto;
    private boolean formatIsMultiColumn;
    private boolean formatInconsistentDates;   // mixed date formats detected
    private boolean formatMixedFonts;          // > 3 distinct font sizes

    // ── Title signals ─────────────────────────────────────────
    private TitleMatch titleMatch;
    private String candidateTitle;
    private String jdTitle;
    private TitleProgression titleProgression;

    // ── Summary signals ───────────────────────────────────────
    private boolean summaryPresent;
    private boolean summaryMentionsTitle;
    private boolean summaryMentionsYoe;
    private boolean summaryMentionsSkills;
    private boolean summaryIsGeneric;          // "passionate team player" type

    // ── YOE signals ───────────────────────────────────────────
    private YoeState yoeState;
    private YoeFit yoeFit;
    private Double calculatedYoe;
    private Double jdYoeMin;
    private Double jdYoeMax;
    private boolean hasUnexplainedGap;
    private double longestGapMonths;
    private boolean isJobHopper;
    private boolean hasConcurrentRoles;
    private boolean hasUnlabelledContracts;
    private List<String> gapDescriptions = new ArrayList<>();
    private boolean hasChronologyIssues;
    private boolean chronologyUnreliable;
    private List<String> chronologyDescriptions = new ArrayList<>();

    // ── Company signals ───────────────────────────────────────
    private CompanyTier currentCompanyTier;
    private boolean companyHasDescriptor;
    private boolean companyTierImproving;
    private boolean companyTierDeclining;
    private String currentCompanyName;

    // ── Skills signals ────────────────────────────────────────
    private SkillsFormat skillsFormat;
    private List<SkillMatchResult> mustHaveResults = new ArrayList<>();
    private List<SkillMatchResult> niceToHaveResults = new ArrayList<>();
    private boolean allMustHavesFound;
    private boolean allMustHavesVisible;       // SURFACE or MID
    private boolean hasBuriedMustHaves;        // found but only in old bullets
    private boolean hasMissingMustHaves;
    private boolean hasAbbreviationMismatches;
    private boolean hasSoftSkillsOnly;
    private boolean hasMixedSoftHard;
    private boolean hasSelfRatedSkills;
    private boolean hasStaleSkills;

    // ── Anomaly signals ───────────────────────────────────────
    private boolean hasSkillAgeMismatch;
    private String skillAgeMismatchDetail;
    private boolean hasTitleInflation;

    // ── Bullet quality signals (NLP-derived) ─────────────────
    private double impactVerbRatio;   // 0–1, fraction of bullets with strong action verbs
    private double metricDensity;     // 0–1, fraction of bullets with quantified claims

    // ── Location / visa signals ───────────────────────────────
    private String jdLocation;           // location from JD (null = not specified)
    private boolean jdLocationStrict;    // true if JD explicitly requires on-site/specific location
    private String candidateLocation;    // from resume header

    // ── Recent role signals ───────────────────────────────────
    private String mostRecentRoleTitle;
    private String mostRecentCompany;
    private int recentRoleCount;         // number of roles in last 3 years

    // ── JD quality signals ────────────────────────────────────
    private JdClarity jdClarity = JdClarity.MEDIUM;

    // ── Projects / portfolio signals ──────────────────────────
    private boolean hasProjectsSection;  // bootcamp/self-taught candidates

    // ── Getters & Setters ────────────────────────────────────

    public boolean isFilenameProfessional() { return filenameProfessional; }
    public void setFilenameProfessional(boolean filenameProfessional) { this.filenameProfessional = filenameProfessional; }

    public boolean isFilenameHasVersioning() { return filenameHasVersioning; }
    public void setFilenameHasVersioning(boolean filenameHasVersioning) { this.filenameHasVersioning = filenameHasVersioning; }

    public boolean isFilenameHasName() { return filenameHasName; }
    public void setFilenameHasName(boolean filenameHasName) { this.filenameHasName = filenameHasName; }

    public boolean isFilenameTooLong() { return filenameTooLong; }
    public void setFilenameTooLong(boolean filenameTooLong) { this.filenameTooLong = filenameTooLong; }

    public boolean isFilenameGeneric() { return filenameGeneric; }
    public void setFilenameGeneric(boolean filenameGeneric) { this.filenameGeneric = filenameGeneric; }

    public String getFilenameIssueDetail() { return filenameIssueDetail; }
    public void setFilenameIssueDetail(String filenameIssueDetail) { this.filenameIssueDetail = filenameIssueDetail; }

    public boolean isFormatWallOfText() { return formatWallOfText; }
    public void setFormatWallOfText(boolean formatWallOfText) { this.formatWallOfText = formatWallOfText; }

    public boolean isFormatTooManyPages() { return formatTooManyPages; }
    public void setFormatTooManyPages(boolean formatTooManyPages) { this.formatTooManyPages = formatTooManyPages; }

    public boolean isFormatFontTooSmall() { return formatFontTooSmall; }
    public void setFormatFontTooSmall(boolean formatFontTooSmall) { this.formatFontTooSmall = formatFontTooSmall; }

    public boolean isFormatHasPhoto() { return formatHasPhoto; }
    public void setFormatHasPhoto(boolean formatHasPhoto) { this.formatHasPhoto = formatHasPhoto; }

    public boolean isFormatIsMultiColumn() { return formatIsMultiColumn; }
    public void setFormatIsMultiColumn(boolean formatIsMultiColumn) { this.formatIsMultiColumn = formatIsMultiColumn; }

    public boolean isFormatInconsistentDates() { return formatInconsistentDates; }
    public void setFormatInconsistentDates(boolean formatInconsistentDates) { this.formatInconsistentDates = formatInconsistentDates; }

    public boolean isFormatMixedFonts() { return formatMixedFonts; }
    public void setFormatMixedFonts(boolean formatMixedFonts) { this.formatMixedFonts = formatMixedFonts; }

    public TitleMatch getTitleMatch() { return titleMatch; }
    public void setTitleMatch(TitleMatch titleMatch) { this.titleMatch = titleMatch; }

    public String getCandidateTitle() { return candidateTitle; }
    public void setCandidateTitle(String candidateTitle) { this.candidateTitle = candidateTitle; }

    public String getJdTitle() { return jdTitle; }
    public void setJdTitle(String jdTitle) { this.jdTitle = jdTitle; }

    public TitleProgression getTitleProgression() { return titleProgression; }
    public void setTitleProgression(TitleProgression titleProgression) { this.titleProgression = titleProgression; }

    public boolean isSummaryPresent() { return summaryPresent; }
    public void setSummaryPresent(boolean summaryPresent) { this.summaryPresent = summaryPresent; }

    public boolean isSummaryMentionsTitle() { return summaryMentionsTitle; }
    public void setSummaryMentionsTitle(boolean summaryMentionsTitle) { this.summaryMentionsTitle = summaryMentionsTitle; }

    public boolean isSummaryMentionsYoe() { return summaryMentionsYoe; }
    public void setSummaryMentionsYoe(boolean summaryMentionsYoe) { this.summaryMentionsYoe = summaryMentionsYoe; }

    public boolean isSummaryMentionsSkills() { return summaryMentionsSkills; }
    public void setSummaryMentionsSkills(boolean summaryMentionsSkills) { this.summaryMentionsSkills = summaryMentionsSkills; }

    public boolean isSummaryIsGeneric() { return summaryIsGeneric; }
    public void setSummaryIsGeneric(boolean summaryIsGeneric) { this.summaryIsGeneric = summaryIsGeneric; }

    public YoeState getYoeState() { return yoeState; }
    public void setYoeState(YoeState yoeState) { this.yoeState = yoeState; }

    public YoeFit getYoeFit() { return yoeFit; }
    public void setYoeFit(YoeFit yoeFit) { this.yoeFit = yoeFit; }

    public Double getCalculatedYoe() { return calculatedYoe; }
    public void setCalculatedYoe(Double calculatedYoe) { this.calculatedYoe = calculatedYoe; }

    public Double getJdYoeMin() { return jdYoeMin; }
    public void setJdYoeMin(Double jdYoeMin) { this.jdYoeMin = jdYoeMin; }

    public Double getJdYoeMax() { return jdYoeMax; }
    public void setJdYoeMax(Double jdYoeMax) { this.jdYoeMax = jdYoeMax; }

    public boolean isHasUnexplainedGap() { return hasUnexplainedGap; }
    public void setHasUnexplainedGap(boolean hasUnexplainedGap) { this.hasUnexplainedGap = hasUnexplainedGap; }

    public double getLongestGapMonths() { return longestGapMonths; }
    public void setLongestGapMonths(double longestGapMonths) { this.longestGapMonths = longestGapMonths; }

    public boolean isJobHopper() { return isJobHopper; }
    public void setJobHopper(boolean jobHopper) { isJobHopper = jobHopper; }

    public boolean isHasConcurrentRoles() { return hasConcurrentRoles; }
    public void setHasConcurrentRoles(boolean hasConcurrentRoles) { this.hasConcurrentRoles = hasConcurrentRoles; }

    public boolean isHasUnlabelledContracts() { return hasUnlabelledContracts; }
    public void setHasUnlabelledContracts(boolean hasUnlabelledContracts) { this.hasUnlabelledContracts = hasUnlabelledContracts; }

    public List<String> getGapDescriptions() { return gapDescriptions; }
    public void setGapDescriptions(List<String> gapDescriptions) { this.gapDescriptions = gapDescriptions; }

    public boolean isHasChronologyIssues() { return hasChronologyIssues; }
    public void setHasChronologyIssues(boolean hasChronologyIssues) { this.hasChronologyIssues = hasChronologyIssues; }

    public boolean isChronologyUnreliable() { return chronologyUnreliable; }
    public void setChronologyUnreliable(boolean chronologyUnreliable) { this.chronologyUnreliable = chronologyUnreliable; }

    public List<String> getChronologyDescriptions() { return chronologyDescriptions; }
    public void setChronologyDescriptions(List<String> chronologyDescriptions) { this.chronologyDescriptions = chronologyDescriptions; }

    public CompanyTier getCurrentCompanyTier() { return currentCompanyTier; }
    public void setCurrentCompanyTier(CompanyTier currentCompanyTier) { this.currentCompanyTier = currentCompanyTier; }

    public boolean isCompanyHasDescriptor() { return companyHasDescriptor; }
    public void setCompanyHasDescriptor(boolean companyHasDescriptor) { this.companyHasDescriptor = companyHasDescriptor; }

    public boolean isCompanyTierImproving() { return companyTierImproving; }
    public void setCompanyTierImproving(boolean companyTierImproving) { this.companyTierImproving = companyTierImproving; }

    public boolean isCompanyTierDeclining() { return companyTierDeclining; }
    public void setCompanyTierDeclining(boolean companyTierDeclining) { this.companyTierDeclining = companyTierDeclining; }

    public String getCurrentCompanyName() { return currentCompanyName; }
    public void setCurrentCompanyName(String currentCompanyName) { this.currentCompanyName = currentCompanyName; }

    public SkillsFormat getSkillsFormat() { return skillsFormat; }
    public void setSkillsFormat(SkillsFormat skillsFormat) { this.skillsFormat = skillsFormat; }

    public List<SkillMatchResult> getMustHaveResults() { return mustHaveResults; }
    public void setMustHaveResults(List<SkillMatchResult> mustHaveResults) { this.mustHaveResults = mustHaveResults; }

    public List<SkillMatchResult> getNiceToHaveResults() { return niceToHaveResults; }
    public void setNiceToHaveResults(List<SkillMatchResult> niceToHaveResults) { this.niceToHaveResults = niceToHaveResults; }

    public boolean isAllMustHavesFound() { return allMustHavesFound; }
    public void setAllMustHavesFound(boolean allMustHavesFound) { this.allMustHavesFound = allMustHavesFound; }

    public boolean isAllMustHavesVisible() { return allMustHavesVisible; }
    public void setAllMustHavesVisible(boolean allMustHavesVisible) { this.allMustHavesVisible = allMustHavesVisible; }

    public boolean isHasBuriedMustHaves() { return hasBuriedMustHaves; }
    public void setHasBuriedMustHaves(boolean hasBuriedMustHaves) { this.hasBuriedMustHaves = hasBuriedMustHaves; }

    public boolean isHasMissingMustHaves() { return hasMissingMustHaves; }
    public void setHasMissingMustHaves(boolean hasMissingMustHaves) { this.hasMissingMustHaves = hasMissingMustHaves; }

    public boolean isHasAbbreviationMismatches() { return hasAbbreviationMismatches; }
    public void setHasAbbreviationMismatches(boolean hasAbbreviationMismatches) { this.hasAbbreviationMismatches = hasAbbreviationMismatches; }

    public boolean isHasSoftSkillsOnly() { return hasSoftSkillsOnly; }
    public void setHasSoftSkillsOnly(boolean hasSoftSkillsOnly) { this.hasSoftSkillsOnly = hasSoftSkillsOnly; }

    public boolean isHasMixedSoftHard() { return hasMixedSoftHard; }
    public void setHasMixedSoftHard(boolean hasMixedSoftHard) { this.hasMixedSoftHard = hasMixedSoftHard; }

    public boolean isHasSelfRatedSkills() { return hasSelfRatedSkills; }
    public void setHasSelfRatedSkills(boolean hasSelfRatedSkills) { this.hasSelfRatedSkills = hasSelfRatedSkills; }

    public boolean isHasStaleSkills() { return hasStaleSkills; }
    public void setHasStaleSkills(boolean hasStaleSkills) { this.hasStaleSkills = hasStaleSkills; }

    public boolean isHasSkillAgeMismatch() { return hasSkillAgeMismatch; }
    public void setHasSkillAgeMismatch(boolean hasSkillAgeMismatch) { this.hasSkillAgeMismatch = hasSkillAgeMismatch; }

    public String getSkillAgeMismatchDetail() { return skillAgeMismatchDetail; }
    public void setSkillAgeMismatchDetail(String skillAgeMismatchDetail) { this.skillAgeMismatchDetail = skillAgeMismatchDetail; }

    public boolean isHasTitleInflation() { return hasTitleInflation; }
    public void setHasTitleInflation(boolean hasTitleInflation) { this.hasTitleInflation = hasTitleInflation; }

    public double getImpactVerbRatio() { return impactVerbRatio; }
    public void setImpactVerbRatio(double impactVerbRatio) { this.impactVerbRatio = impactVerbRatio; }

    public double getMetricDensity() { return metricDensity; }
    public void setMetricDensity(double metricDensity) { this.metricDensity = metricDensity; }

    public String getJdLocation() { return jdLocation; }
    public void setJdLocation(String jdLocation) { this.jdLocation = jdLocation; }
    public boolean isJdLocationStrict() { return jdLocationStrict; }
    public void setJdLocationStrict(boolean jdLocationStrict) { this.jdLocationStrict = jdLocationStrict; }
    public String getCandidateLocation() { return candidateLocation; }
    public void setCandidateLocation(String candidateLocation) { this.candidateLocation = candidateLocation; }

    public String getMostRecentRoleTitle() { return mostRecentRoleTitle; }
    public void setMostRecentRoleTitle(String mostRecentRoleTitle) { this.mostRecentRoleTitle = mostRecentRoleTitle; }
    public String getMostRecentCompany() { return mostRecentCompany; }
    public void setMostRecentCompany(String mostRecentCompany) { this.mostRecentCompany = mostRecentCompany; }
    public int getRecentRoleCount() { return recentRoleCount; }
    public void setRecentRoleCount(int recentRoleCount) { this.recentRoleCount = recentRoleCount; }

    public JdClarity getJdClarity() { return jdClarity; }
    public void setJdClarity(JdClarity jdClarity) { this.jdClarity = jdClarity; }

    public boolean isHasProjectsSection() { return hasProjectsSection; }
    public void setHasProjectsSection(boolean hasProjectsSection) { this.hasProjectsSection = hasProjectsSection; }
}
