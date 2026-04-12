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
import com.resumestudio.reviewer.nlg.AiReviewService;
import com.resumestudio.reviewer.nlg.FeedbackGenerator;
import com.resumestudio.reviewer.signals.*;
import com.resumestudio.reviewer.signals.CoherenceEngine;
import com.resumestudio.reviewer.skills.*;
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
    private final CoherenceEngine coherenceEngine;
    private final FeedbackGenerator feedbackGenerator;
    private final AiReviewService aiReviewService;
    private final OutcomeTracker outcomeTracker;
    private final com.resumestudio.reviewer.nlp.NlpService nlpService;
    private final com.resumestudio.reviewer.nlp.BulletEnricher bulletEnricher;

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
                            CoherenceEngine coherenceEngine,
                            FeedbackGenerator feedbackGenerator,
                            AiReviewService aiReviewService,
                            OutcomeTracker outcomeTracker,
                            com.resumestudio.reviewer.nlp.NlpService nlpService,
                            com.resumestudio.reviewer.nlp.BulletEnricher bulletEnricher) {
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
        this.coherenceEngine = coherenceEngine;
        this.feedbackGenerator = feedbackGenerator;
        this.aiReviewService = aiReviewService;
        this.outcomeTracker = outcomeTracker;
        this.nlpService = nlpService;
        this.bulletEnricher = bulletEnricher;
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

            // Non-English detection — warn but continue
            if (isLikelyNonEnglish(raw.getFullText())) {
                log.warn("Resume appears to be non-English — signal quality may be reduced");
                // Surface as a red flag in the report (added post-coherence)
            }

            // Layer 3–5 — Compute all signals
            ResumeSignals signals = computeSignals(resume, jd, raw);

            // Layer 5 — Coherence
            CoherenceEngine.CoherenceResult coherence = coherenceEngine.check(signals);

            // Layer 6 — Classification
            ClassificationEngine.ClassificationResult classification = classificationEngine.classify(signals, coherence);

            // Layer 7+8+9 — AI enrichment + assembly
            FeedbackReport.RoleContext roleContext = new FeedbackReport.RoleContext();
            roleContext.setTitle(jd.getRoleTitle());
            roleContext.setRequired(jd.getMustHaveSkills());
            roleContext.setPreferred(jd.getNiceToHaveSkills());
            roleContext.setInferred(jd.getImpliedSkills());
            roleContext.setDomain(inferDomain(jd.getRoleTitle()));

            // Build redFlags from coherence + JD quality warnings
            List<FeedbackReport.RedFlag> redFlags = new java.util.ArrayList<>(coherence.flags().stream()
                .map(f -> new FeedbackReport.RedFlag(f.type(), f.severity(), f.detail()))
                .toList());
            if (classification.jdClarity() == com.resumestudio.reviewer.model.enums.JdClarity.LOW) {
                redFlags.add(new FeedbackReport.RedFlag("JD_CLARITY_LOW",
                    com.resumestudio.reviewer.model.enums.ImpactLevel.MEDIUM,
                    "The job description is vague or missing a requirements section. Results may be less accurate — consider pasting a more detailed JD."));
            }
            if (jd.getMustHaveSkills().isEmpty()) {
                redFlags.add(new FeedbackReport.RedFlag("NO_SKILLS_IN_JD",
                    com.resumestudio.reviewer.model.enums.ImpactLevel.HIGH,
                    "No technical skills could be extracted from the job description. Skill matching is unavailable for this review."));
            }

            FeedbackReport.Builder builder = FeedbackReport.builder()
                .verdict(classification.verdict())
                .confidence(classification.confidence())
                .interviewLikelihood(classification.interviewLikelihood())
                .scanDuration(classification.scanDuration())
                .seniorityCalibration(classification.seniorityCalibration())
                .tailoringScore(classification.tailoringScore())
                .jdClarity(classification.jdClarity())
                .recruiterType(classification.recruiterType())
                .competitiveContext(classification.competitiveContext())
                .roleContext(roleContext)
                .redFlags(redFlags);

            FeedbackReport report = aiReviewService.enrich(builder, signals, classification, jd, resume, coherence).build();
            outcomeTracker.track(report, signals); // Layer 10 — async, non-blocking
            return report;
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
            CoherenceEngine.CoherenceResult coherence = coherenceEngine.check(signals);
            ClassificationEngine.ClassificationResult classification = classificationEngine.classify(signals, coherence);

            FeedbackReport.RoleContext roleContext = new FeedbackReport.RoleContext();
            roleContext.setTitle(jd.getRoleTitle());
            roleContext.setRequired(jd.getMustHaveSkills());
            roleContext.setPreferred(jd.getNiceToHaveSkills());
            roleContext.setInferred(jd.getImpliedSkills());
            roleContext.setDomain(inferDomain(jd.getRoleTitle()));

            List<FeedbackReport.RedFlag> redFlags = new java.util.ArrayList<>(coherence.flags().stream()
                .map(f -> new FeedbackReport.RedFlag(f.type(), f.severity(), f.detail()))
                .toList());
            if (classification.jdClarity() == com.resumestudio.reviewer.model.enums.JdClarity.LOW) {
                redFlags.add(new FeedbackReport.RedFlag("JD_CLARITY_LOW",
                    com.resumestudio.reviewer.model.enums.ImpactLevel.MEDIUM,
                    "The job description is vague or missing a requirements section. Results may be less accurate."));
            }
            if (jd.getMustHaveSkills().isEmpty()) {
                redFlags.add(new FeedbackReport.RedFlag("NO_SKILLS_IN_JD",
                    com.resumestudio.reviewer.model.enums.ImpactLevel.HIGH,
                    "No technical skills could be extracted from the job description. Skill matching is unavailable for this review."));
            }

            FeedbackReport.Builder builder = FeedbackReport.builder()
                .verdict(classification.verdict())
                .confidence(classification.confidence())
                .interviewLikelihood(classification.interviewLikelihood())
                .scanDuration(classification.scanDuration())
                .seniorityCalibration(classification.seniorityCalibration())
                .tailoringScore(classification.tailoringScore())
                .jdClarity(classification.jdClarity())
                .recruiterType(classification.recruiterType())
                .competitiveContext(classification.competitiveContext())
                .roleContext(roleContext)
                .redFlags(redFlags);

            FeedbackReport report = aiReviewService.enrich(builder, signals, classification, jd, resume, coherence).build();
            outcomeTracker.track(report, signals);
            return report;
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
            // Deduplicate by canonical name (case-insensitive) — keep first occurrence
            List<Skill> deduped = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (Skill s : skillResult.getSkills()) {
                String key = (s.getCanonicalName() != null ? s.getCanonicalName() : s.getRawName())
                    .toLowerCase().trim();
                if (seen.add(key)) deduped.add(s);
            }
            resume.setSkills(deduped);
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

        // Bullet quality (NLP) + Layer 2b enrichment
        List<String> allBullets = resume.getExperience().stream()
            .flatMap(exp -> exp.getBullets().stream())
            .toList();
        signals.setImpactVerbRatio(nlpService.impactVerbRatio(allBullets));
        signals.setMetricDensity(nlpService.metricDensity(allBullets));

        // Layer 2b — full bullet enrichment (scope, specificity, duplicates, top-5 scoring)
        com.resumestudio.reviewer.nlp.BulletEnricher.EnrichmentResult enrichment =
            bulletEnricher.enrich(resume.getExperience(), jd.getMustHaveSkills());
        resume.setTopBullets(enrichment.topBullets());

        // JD quality
        if (jd.getJdClarity() != null) signals.setJdClarity(jd.getJdClarity());

        // Projects section — relevant for bootcamp/self-taught candidates
        boolean hasProjects = resume.getProjects() != null && !resume.getProjects().isEmpty();
        signals.setHasProjectsSection(hasProjects);

        // For junior/fresher roles: if candidate has no formal experience but has projects,
        // treat as CANNOT_DETERMINE (not penalised) rather than MISSING
        boolean isFresherRole = jd.getYoeMin() == null || jd.getYoeMin() <= 1.0;
        if (isFresherRole && hasProjects && signals.getYoeFit() == com.resumestudio.reviewer.model.enums.YoeFit.CANNOT_DETERMINE) {
            // Projects count as evidence — don't penalise further
            signals.setYoeState(com.resumestudio.reviewer.model.enums.YoeState.CALCULABLE);
        }

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

    /**
     * Heuristic: if > 30% of characters are non-ASCII, likely non-English.
     * Doesn't block processing — just flags for logging/warning.
     */
    private boolean isLikelyNonEnglish(String text) {
        if (text == null || text.length() < 100) return false;
        long nonAscii = text.chars().filter(c -> c > 127).count();
        return (double) nonAscii / text.length() > 0.30;
    }

    private String inferDomain(String roleTitle) {
        if (roleTitle == null) return null;
        String lower = roleTitle.toLowerCase();
        if (lower.contains("backend") || lower.contains("java") || lower.contains("python") || lower.contains("api")) return "Backend Engineering";
        if (lower.contains("frontend") || lower.contains("react") || lower.contains("ui")) return "Frontend Engineering";
        if (lower.contains("full stack") || lower.contains("fullstack")) return "Full Stack Engineering";
        if (lower.contains("devops") || lower.contains("sre") || lower.contains("platform") || lower.contains("infrastructure")) return "DevOps / Platform";
        if (lower.contains("data") || lower.contains("ml") || lower.contains("machine learning") || lower.contains("ai")) return "Data / ML";
        if (lower.contains("mobile") || lower.contains("ios") || lower.contains("android")) return "Mobile Engineering";
        if (lower.contains("security") || lower.contains("infosec")) return "Security";
        if (lower.contains("qa") || lower.contains("test") || lower.contains("sdet")) return "Quality Engineering";
        if (lower.contains("product")) return "Product Management";
        if (lower.contains("manager") || lower.contains("director")) return "Engineering Management";
        return "Software Engineering";
    }
}
