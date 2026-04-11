package com.resumestudio.reviewer;

import com.resumestudio.reviewer.api.JdFetchService;
import com.resumestudio.reviewer.classification.ClassificationEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main pipeline orchestrator.
 * Wires all layers from ingest → signals → classification → feedback.
 */
@Service
public class ReviewerPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewerPipeline.class);

    private final ResumeIngestService ingestService;
    private final JdParserService jdParser;
    private final JdFetchService jdFetchService;
    private final HeaderExtractor headerExtractor;
    private final ExperienceExtractor experienceExtractor;
    private final EducationExtractor educationExtractor;
    private final SkillsSectionExtractor skillsExtractor;
    private final SummaryExtractor summaryExtractor;
    private final SemanticExtractor semanticExtractor;
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
    private final com.resumestudio.reviewer.nlp.NlpService nlpService;

    public ReviewerPipeline(ResumeIngestService ingestService,
                            JdParserService jdParser,
                            JdFetchService jdFetchService,
                            HeaderExtractor headerExtractor,
                            ExperienceExtractor experienceExtractor,
                            EducationExtractor educationExtractor,
                            SkillsSectionExtractor skillsExtractor,
                            SummaryExtractor summaryExtractor,
                            SemanticExtractor semanticExtractor,
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
                            FeedbackGenerator feedbackGenerator,
                            com.resumestudio.reviewer.nlp.NlpService nlpService) {
        this.ingestService = ingestService;
        this.jdParser = jdParser;
        this.jdFetchService = jdFetchService;
        this.headerExtractor = headerExtractor;
        this.experienceExtractor = experienceExtractor;
        this.educationExtractor = educationExtractor;
        this.skillsExtractor = skillsExtractor;
        this.summaryExtractor = summaryExtractor;
        this.semanticExtractor = semanticExtractor;
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
        this.nlpService = nlpService;
    }

    public FeedbackReport review(MultipartFile file, String jdText) {
        try {
            // Layer 0 — Ingest
            RawDocument raw = ingestService.ingest(file);

            // Layer 0a — Resolve JD (URL or text)
            String resolvedJdText = jdFetchService.resolve(jdText);

            // Layer 0b — Parse JD
            JobDescription jd = jdParser.parse(resolvedJdText);

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

    /**
     * Evaluation/testing path that skips file ingest and uses plain resume text.
     * This is intended for blind-label recruiter-agreement evaluation datasets.
     */
    public FeedbackReport reviewRawText(String resumeText, String jdText) {
        try {
            RawDocument raw = syntheticRawDocument(resumeText);
            JobDescription jd = jdParser.parse(jdText);
            Resume resume = buildResume(raw);
            ResumeSignals signals = computeSignals(resume, jd, raw);
            ClassificationEngine.ClassificationResult classification = classificationEngine.classify(signals);
            List<TimelineEvent> timeline = timelineEngine.build(signals, classification.verdict());
            FeedbackGenerator.FeedbackOutput feedback = feedbackGenerator.generate(signals, classification.verdict());

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
            throw new RuntimeException("Failed to review resume from raw text", e);
        }
    }

    private RawDocument syntheticRawDocument(String resumeText) {
        String text = resumeText != null ? resumeText : "";

        RawDocument.RawPage page = new RawDocument.RawPage();
        page.setPageNumber(1);
        page.setText(text);
        page.setBlocks(new ArrayList<>());

        RawDocument raw = new RawDocument();
        raw.setFilename("resume.txt");
        raw.setMimeType("text/plain");
        raw.setFullText(text);
        raw.setPages(List.of(page));
        raw.setScanned(false);
        raw.setParseConfidence(text.isBlank() ? 0.0 : 0.8);
        return raw;
    }

    private Resume buildResume(RawDocument raw) {
        Resume resume = new Resume();
        resume.setRawFilename(raw.getFilename());
        
        // Extract header (modifies resume in place)
        headerExtractor.extract(raw, resume);
        
        // Extract sections from full text
        String fullText = raw.getFullText();
        SemanticExtractor.SectionMap sections = extractSections(fullText);
        
        // Extract summary
        if (sections.summary != null) {
            summaryExtractor.extract(sections.summary, resume);
        }
        
        // Extract experience
        if (sections.experience != null) {
            List<WorkExperience> experience = experienceExtractor.extract(sections.experience);
            resume.setExperience(experience);
        }

        if (sections.education != null) {
            List<Education> education = educationExtractor.extract(sections.education);
            resume.setEducation(education);
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

        // Summary - use SOTA analysis with ESCO taxonomy
        SummaryExtractor.SummaryAnalysis summaryAnalysis = summaryExtractor.analyse(resume.getSummaryText(), jd.getRoleTitle());
        signals.setSummaryPresent(summaryAnalysis.isPresent());
        signals.setSummaryMentionsTitle(summaryAnalysis.isMentionsTitle());
        signals.setSummaryMentionsYoe(summaryAnalysis.isMentionsYoe());
        signals.setSummaryMentionsSkills(summaryAnalysis.isMentionsSkills());
        signals.setSummaryIsGeneric(summaryAnalysis.isGeneric());

        // YOE
        yoeCalculator.compute(resume.getExperience(), resume.getTotalYoeYears(), 
            resume.isYoeExplicitInSummary(), jd.getYoeMin(), jd.getYoeMax(), signals, resume.getEducation());

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

        // Compute aggregated skill signals.
        // Guard: allMatch on an empty list returns true vacuously — treat "no JD skills" as unknown, not passing.
        boolean hasJdSkills = !mustHaveResults.isEmpty();
        signals.setAllMustHavesFound(hasJdSkills && mustHaveResults.stream().allMatch(r -> r.getVisibility() != com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING));
        signals.setAllMustHavesVisible(hasJdSkills && mustHaveResults.stream().allMatch(r ->
            r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.SURFACE ||
            r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MID));
        signals.setHasBuriedMustHaves(mustHaveResults.stream().anyMatch(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.BURIED));
        signals.setHasMissingMustHaves(mustHaveResults.stream().anyMatch(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING));

        // Anomalies
        anomalyDetector.detect(resume.getSkills(), resume.getExperience(), resume.getSummaryText(), signals);

        // Bullet quality (NLP)
        List<String> allBullets = resume.getExperience().stream()
            .flatMap(exp -> exp.getBullets().stream())
            .toList();
        signals.setImpactVerbRatio(nlpService.impactVerbRatio(allBullets));
        signals.setMetricDensity(nlpService.metricDensity(allBullets));

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
    private SemanticExtractor.SectionMap extractSections(String fullText) {
        // Use semantic section detection instead of rigid regex
        return semanticExtractor.extractSections(fullText);
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
