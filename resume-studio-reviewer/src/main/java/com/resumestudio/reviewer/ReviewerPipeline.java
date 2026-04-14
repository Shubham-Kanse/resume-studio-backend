package com.resumestudio.reviewer;

import com.resumestudio.reviewer.api.JdFetchService;
import com.resumestudio.reviewer.classification.ClassificationEngine;
import com.resumestudio.reviewer.extraction.JdParserService;
import com.resumestudio.reviewer.extraction.ResumeExtractionService;
import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.ingest.ResumeIngestService;
import com.resumestudio.reviewer.model.*;
import com.resumestudio.reviewer.model.enums.ImpactLevel;
import com.resumestudio.reviewer.model.enums.JdClarity;
import com.resumestudio.reviewer.nlg.AiReviewService;
import com.resumestudio.reviewer.signals.CoherenceEngine;
import com.resumestudio.reviewer.signals.ResumeScoreCalculator;
import com.resumestudio.reviewer.signals.SignalComputationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

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
    private final ResumeExtractionService extractionService;
    private final SignalComputationService signalService;
    private final CoherenceEngine coherenceEngine;
    private final ClassificationEngine classificationEngine;
    private final AiReviewService aiReviewService;
    private final OutcomeTracker outcomeTracker;
    private final ResumeScoreCalculator scoreCalculator;
    private final com.resumestudio.reviewer.nlg.DeepDiveGenerator deepDiveGenerator;

    public ReviewerPipeline(ResumeIngestService ingestService,
                            JdParserService jdParser,
                            JdFetchService jdFetchService,
                            ResumeExtractionService extractionService,
                            SignalComputationService signalService,
                            CoherenceEngine coherenceEngine,
                            ClassificationEngine classificationEngine,
                            AiReviewService aiReviewService,
                            OutcomeTracker outcomeTracker,
                            ResumeScoreCalculator scoreCalculator,
                            com.resumestudio.reviewer.nlg.DeepDiveGenerator deepDiveGenerator) {
        this.ingestService = ingestService;
        this.jdParser = jdParser;
        this.jdFetchService = jdFetchService;
        this.extractionService = extractionService;
        this.signalService = signalService;
        this.coherenceEngine = coherenceEngine;
        this.classificationEngine = classificationEngine;
        this.aiReviewService = aiReviewService;
        this.outcomeTracker = outcomeTracker;
        this.scoreCalculator = scoreCalculator;
        this.deepDiveGenerator = deepDiveGenerator;
    }

    /** Deep dive — runs full pipeline then generates section-by-section review. */
    public DeepDiveReport deepDive(MultipartFile file, String jdText) {
        try {
            RawDocument raw = ingestService.ingest(file);
            JobDescription jd = jdParser.parse(jdFetchService.resolve(jdText));
            Resume resume = extractionService.extract(raw);
            ResumeSignals signals = signalService.compute(resume, jd, raw);
            DeepDiveReport report = deepDiveGenerator.generate(resume, signals, jd);
            aiReviewService.enrichDeepDive(report, jd);
            return report;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate deep dive", e);
        }
    }

    public FeedbackReport review(MultipartFile file, String jdText) {
        try {
            RawDocument raw = ingestService.ingest(file);
            JobDescription jd = jdParser.parse(jdFetchService.resolve(jdText));
            Resume resume = extractionService.extract(raw);

            if (isLikelyNonEnglish(raw.getFullText())) {
                log.warn("Resume appears to be non-English — signal quality may be reduced");
            }

            return buildReport(resume, jd, raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to review resume", e);
        }
    }

    /** Evaluation path — skips file ingest, uses plain text. */
    public FeedbackReport reviewRawText(String resumeText, String jdText) {
        try {
            RawDocument raw = syntheticRawDocument(resumeText);
            JobDescription jd = jdParser.parse(jdText);
            Resume resume = extractionService.extract(raw);
            return buildReport(resume, jd, raw);
        } catch (Exception e) {
            throw new RuntimeException("Failed to review resume from raw text", e);
        }
    }

    private FeedbackReport buildReport(Resume resume, JobDescription jd, RawDocument raw) {
        ResumeSignals signals = signalService.compute(resume, jd, raw);
        CoherenceEngine.CoherenceResult coherence = coherenceEngine.check(signals);
        ClassificationEngine.ClassificationResult classification = classificationEngine.classify(signals, coherence);

        FeedbackReport.RoleContext roleContext = new FeedbackReport.RoleContext();
        roleContext.setTitle(jd.getRoleTitle());
        roleContext.setRequired(jd.getMustHaveSkills());
        roleContext.setPreferred(jd.getNiceToHaveSkills());
        roleContext.setInferred(jd.getImpliedSkills());
        roleContext.setDomain(extractionService.inferDomain(jd.getRoleTitle()));

        List<FeedbackReport.RedFlag> redFlags = new ArrayList<>(coherence.flags().stream()
            .map(f -> new FeedbackReport.RedFlag(f.type(), f.severity(), f.detail()))
            .toList());
        if (classification.jdClarity() == JdClarity.LOW) {
            redFlags.add(new FeedbackReport.RedFlag("JD_CLARITY_LOW", ImpactLevel.MEDIUM,
                "The job description is vague or missing a requirements section. Results may be less accurate — consider pasting a more detailed JD."));
        }
        if (jd.getMustHaveSkills().isEmpty()) {
            redFlags.add(new FeedbackReport.RedFlag("NO_SKILLS_IN_JD", ImpactLevel.HIGH,
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
            .redFlags(redFlags)
            .score(scoreCalculator.calculate(signals));

        FeedbackReport report = aiReviewService.enrich(builder, signals, classification, jd, resume, coherence).build();
        outcomeTracker.track(report, signals);
        return report;
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

    private boolean isLikelyNonEnglish(String text) {
        if (text == null || text.length() < 100) return false;
        long nonAscii = text.chars().filter(c -> c > 127).count();
        return (double) nonAscii / text.length() > 0.30;
    }
}
