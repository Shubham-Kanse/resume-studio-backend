package com.resumestudio.reviewer;

import com.resumestudio.reviewer.classification.ClassificationEngine;
import com.resumestudio.reviewer.extraction.*;
import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.ingest.ResumeIngestService;
import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.SkillsFormat;
import com.resumestudio.reviewer.nlg.FeedbackGenerator;
import com.resumestudio.reviewer.signals.*;
import com.resumestudio.reviewer.skills.*;
import com.resumestudio.reviewer.timeline.TimelineEngine;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main pipeline orchestrator.
 * Wires all layers from ingest → signals → classification → feedback.
 */
@Service
public class ReviewerPipeline {

    private final ResumeIngestService ingestService;
    private final JdParserService jdParser;
    private final HeaderExtractor headerExtractor;
    private final ExperienceExtractor experienceExtractor;
    private final SkillsSectionExtractor skillsExtractor;
    private final SummaryExtractor summaryExtractor;
    private final FilenameSignalCalculator filenameCalculator;
    private final FormatSignalCalculator formatCalculator;
    private final TitleMatchCalculator titleCalculator;
    private final YoeSignalCalculator yoeCalculator;
    private final CompanySignalCalculator companyCalculator;
    private final SkillMatchEngine skillMatchEngine;
    private final SkillVisibilityAnalyser visibilityAnalyser;
    private final SkillsFormatAnalyser formatAnalyser;
    private final AnomalyDetector anomalyDetector;
    private final ClassificationEngine classificationEngine;
    private final TimelineEngine timelineEngine;
    private final FeedbackGenerator feedbackGenerator;

    public ReviewerPipeline(ResumeIngestService ingestService,
                            JdParserService jdParser,
                            HeaderExtractor headerExtractor,
                            ExperienceExtractor experienceExtractor,
                            SkillsSectionExtractor skillsExtractor,
                            SummaryExtractor summaryExtractor,
                            FilenameSignalCalculator filenameCalculator,
                            FormatSignalCalculator formatCalculator,
                            TitleMatchCalculator titleCalculator,
                            YoeSignalCalculator yoeCalculator,
                            CompanySignalCalculator companyCalculator,
                            SkillMatchEngine skillMatchEngine,
                            SkillVisibilityAnalyser visibilityAnalyser,
                            SkillsFormatAnalyser formatAnalyser,
                            AnomalyDetector anomalyDetector,
                            ClassificationEngine classificationEngine,
                            TimelineEngine timelineEngine,
                            FeedbackGenerator feedbackGenerator) {
        this.ingestService = ingestService;
        this.jdParser = jdParser;
        this.headerExtractor = headerExtractor;
        this.experienceExtractor = experienceExtractor;
        this.skillsExtractor = skillsExtractor;
        this.summaryExtractor = summaryExtractor;
        this.filenameCalculator = filenameCalculator;
        this.formatCalculator = formatCalculator;
        this.titleCalculator = titleCalculator;
        this.yoeCalculator = yoeCalculator;
        this.companyCalculator = companyCalculator;
        this.skillMatchEngine = skillMatchEngine;
        this.visibilityAnalyser = visibilityAnalyser;
        this.formatAnalyser = formatAnalyser;
        this.anomalyDetector = anomalyDetector;
        this.classificationEngine = classificationEngine;
        this.timelineEngine = timelineEngine;
        this.feedbackGenerator = feedbackGenerator;
    }

    public FeedbackReport review(MultipartFile file, String jdText) {
        try {
            // Layer 0 — Ingest
            RawDocument raw = ingestService.ingest(file);

            // Layer 0b — Parse JD
            JobDescription jd = jdParser.parse(jdText);

            // Layer 1 & 2 — Extract resume structure
            Resume resume = buildResume(raw);

            // Layer 3–5 — Compute all signals
            ResumeSignals signals = computeSignals(resume, jd, raw);

            // Layer 6 — Classification
            ClassificationEngine.ClassificationResult classification = classificationEngine.classify(signals);

            // Layer 7 — Timeline
            List<TimelineEvent> timeline = timelineEngine.build(signals, classification.verdict());

            // Layer 8 — Feedback
            FeedbackGenerator.FeedbackOutput feedback = feedbackGenerator.generate(signals, classification.verdict());

            // Assemble final report
            return FeedbackReport.builder()
                .verdict(classification.verdict())
                .confidence(classification.confidence())
                .roleContext(new FeedbackReport.RoleContext(jd.getRoleTitle(), jd.getMustHaveSkills()))
                .summaryParagraph(feedback.summaryParagraph())
                .timeline(timeline)
                .signals(feedback.signals())
                .fixes(feedback.fixes())
                .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to review resume", e);
        }
    }

    private Resume buildResume(RawDocument raw) {
        Resume resume = new Resume();
        resume.setRawFilename(raw.getFilename());
        
        // Extract header (modifies resume in place)
        headerExtractor.extract(raw, resume);
        
        // Extract sections from full text
        String fullText = raw.getFullText();
        SectionMap sections = extractSections(fullText);
        
        // Extract summary
        if (sections.summary != null) {
            summaryExtractor.extract(sections.summary, resume);
        }
        
        // Extract experience
        if (sections.experience != null) {
            List<WorkExperience> experience = experienceExtractor.extract(sections.experience);
            resume.setExperience(experience);
        }
        
        // Extract skills
        if (sections.skills != null) {
            SkillsSectionExtractor.ExtractionResult skillResult = 
                skillsExtractor.extract(sections.skills, extractAllBullets(fullText));
            resume.setSkills(skillResult.getSkills());
        }
        
        return resume;
    }

    private ResumeSignals computeSignals(Resume resume, JobDescription jd, RawDocument raw) {
        ResumeSignals signals = new ResumeSignals();

        // Filename
        filenameCalculator.compute(raw.getFilename(), signals);

        // Format
        formatCalculator.compute(raw, resume.getTotalYoeYears() != null ? resume.getTotalYoeYears() : 0.0, signals);

        // Title
        titleCalculator.compute(resume.getCurrentTitle(), jd.getRoleTitle(), resume.getExperience(), signals);

        // Summary
        if (resume.getSummaryText() != null) {
            signals.setSummaryPresent(true);
            String summary = resume.getSummaryText().toLowerCase();
            signals.setSummaryMentionsTitle(summary.contains(jd.getRoleTitle().toLowerCase()));
            signals.setSummaryMentionsYoe(summary.matches(".*\\d+\\s*(year|yr).*"));
            signals.setSummaryMentionsSkills(jd.getMustHaveSkills().stream()
                .anyMatch(skill -> summary.contains(skill.toLowerCase())));
            signals.setSummaryIsGeneric(summary.matches(".*(passionate|team player|hard working|dedicated).*"));
        }

        // YOE
        yoeCalculator.compute(resume.getExperience(), resume.getTotalYoeYears(), 
            resume.isYoeExplicitInSummary(), jd.getYoeMin(), jd.getYoeMax(), signals);

        // Company
        companyCalculator.compute(resume.getExperience(), resume.getCurrentCompany(), 
            resume.getCompanyDescriptor(), signals);

        // Skills visibility - enrich skills with visibility info
        visibilityAnalyser.analyse(resume.getSkills(), resume.getExperience(), resume.getSummaryText());

        // Skills format - refine based on JD must-haves
        SkillsFormat initialFormat = detectInitialFormat(resume.getSkills());
        SkillsFormat refinedFormat = formatAnalyser.refine(initialFormat, resume.getSkills(), jd.getMustHaveSkills());
        signals.setSkillsFormat(refinedFormat);

        // Skills matching
        List<SkillMatchResult> mustHaveResults = skillMatchEngine.matchAll(jd.getMustHaveSkills(), resume.getSkills(), true);
        List<SkillMatchResult> niceToHaveResults = skillMatchEngine.matchAll(jd.getNiceToHaveSkills(), resume.getSkills(), false);
        signals.setMustHaveResults(mustHaveResults);
        signals.setNiceToHaveResults(niceToHaveResults);

        // Compute aggregated skill signals
        signals.setAllMustHavesFound(mustHaveResults.stream().allMatch(r -> r.getVisibility() != com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING));
        signals.setAllMustHavesVisible(mustHaveResults.stream().allMatch(r -> 
            r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.SURFACE || 
            r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MID));
        signals.setHasBuriedMustHaves(mustHaveResults.stream().anyMatch(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.BURIED));
        signals.setHasMissingMustHaves(mustHaveResults.stream().anyMatch(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING));

        // Anomalies
        anomalyDetector.detect(resume.getSkills(), resume.getExperience(), resume.getSummaryText(), signals);

        return signals;
    }

    private SkillsFormat detectInitialFormat(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) return SkillsFormat.NO_SECTION;
        
        // Check if categorized
        boolean hasCategories = skills.stream().anyMatch(s -> s.getCategory() != null && !s.getCategory().isBlank());
        if (hasCategories) return SkillsFormat.CATEGORISED_UNORDERED;
        
        // Default to flat unordered
        return SkillsFormat.FLAT_UNORDERED;
    }

    // Simple section extraction using regex
    private static class SectionMap {
        String summary;
        String experience;
        String skills;
    }

    private SectionMap extractSections(String fullText) {
        SectionMap map = new SectionMap();
        
        // Summary section (also called Profile, About, Objective)
        Pattern summaryPattern = Pattern.compile(
            "(?i)(summary|profile|about|objective|professional summary)\\s*[:\\n]([\\s\\S]{20,500}?)(?=\\n\\s*[A-Z][a-z]+\\s*:|$)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher summaryMatcher = summaryPattern.matcher(fullText);
        if (summaryMatcher.find()) {
            map.summary = summaryMatcher.group(2).trim();
        }
        
        // Experience section
        Pattern expPattern = Pattern.compile(
            "(?i)(experience|work history|employment|professional experience)\\s*[:\\n]([\\s\\S]{50,}?)(?=\\n\\s*(education|skills|projects|certifications)|$)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher expMatcher = expPattern.matcher(fullText);
        if (expMatcher.find()) {
            map.experience = expMatcher.group(2).trim();
        }
        
        // Skills section
        Pattern skillsPattern = Pattern.compile(
            "(?i)(skills|technical skills|core competencies|technologies)\\s*[:\\n]([\\s\\S]{20,800}?)(?=\\n\\s*[A-Z][a-z]+\\s*:|$)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher skillsMatcher = skillsPattern.matcher(fullText);
        if (skillsMatcher.find()) {
            map.skills = skillsMatcher.group(2).trim();
        }
        
        return map;
    }

    private List<String> extractAllBullets(String text) {
        Pattern bulletPattern = Pattern.compile("^\\s*[•\\-*▪◦➤►→]\\s*(.+)$", Pattern.MULTILINE);
        Matcher matcher = bulletPattern.matcher(text);
        List<String> bullets = new java.util.ArrayList<>();
        while (matcher.find()) {
            bullets.add(matcher.group(1).trim());
        }
        return bullets;
    }
}

