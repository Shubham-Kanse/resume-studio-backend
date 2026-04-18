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
    private final com.resumestudio.reviewer.timeline.TimelineEngine timelineEngine;

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
                            com.resumestudio.reviewer.nlg.DeepDiveGenerator deepDiveGenerator,
                            com.resumestudio.reviewer.timeline.TimelineEngine timelineEngine) {
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
        this.timelineEngine = timelineEngine;
    }

    /** Deep dive — runs full pipeline then generates section-by-section review. */
    public DeepDiveReport deepDive(MultipartFile file, String jdText) throws java.io.IOException {
        RawDocument raw = ingestService.ingest(file);
        JobDescription jd = jdParser.parse(jdFetchService.resolve(jdText));
        Resume resume = extractionService.extract(raw);
        ResumeSignals signals = signalService.compute(resume, jd, raw);
        DeepDiveReport report = deepDiveGenerator.generate(resume, signals, jd);
        aiReviewService.enrichDeepDive(report, jd);
        return report;
    }

    /**
     * Deep dive reusing pre-computed signals from a prior review call.
     * Avoids full re-ingestion when review + deep dive run in the same session.
     */
    public DeepDiveReport deepDiveWithSignals(MultipartFile file, String jdText,
            ResumeSignals cachedSignals, Resume cachedResume) throws java.io.IOException {
        if (cachedSignals == null || cachedResume == null) {
            return deepDive(file, jdText);
        }
        JobDescription jd = jdParser.parse(jdFetchService.resolve(jdText));
        DeepDiveReport report = deepDiveGenerator.generate(cachedResume, cachedSignals, jd);
        aiReviewService.enrichDeepDive(report, jd);
        return report;
    }

    public FeedbackReport review(MultipartFile file, String jdText) throws java.io.IOException {
        return review(file, jdText, null);
    }

    public FeedbackReport review(MultipartFile file, String jdText, String userId) throws java.io.IOException {
        return reviewFull(file, jdText, userId).report();
    }

    /** Returns report + intermediate objects for caching by the SSE controller. */
    public ReviewResult reviewFull(MultipartFile file, String jdText, String userId) throws java.io.IOException {
        RawDocument raw = ingestService.ingest(file);
        String resolvedJd = jdFetchService.resolve(jdText);

        boolean jdFromUrl = jdText != null && jdText.trim().matches("^https?://.*");
        JobDescription jd = jdParser.parse(resolvedJd);
        if (jdFromUrl && (resolvedJd == null || resolvedJd.trim().length() < 200 || jd.getJdClarity() == JdClarity.LOW)) {
            log.warn("JD fetched from URL appears incomplete ({} chars, clarity={})", resolvedJd == null ? 0 : resolvedJd.length(), jd.getJdClarity());
        }

        Resume resume = extractionService.extract(raw);

        if (isLikelyNonEnglish(raw.getFullText())) {
            log.warn("Resume appears to be non-English — signal quality may be reduced");
        }

        boolean jdIncomplete = jdFromUrl && (resolvedJd == null || resolvedJd.trim().length() < 200);
        FeedbackReport report = buildReport(resume, jd, raw, jdIncomplete, userId);
        // Retrieve signals from the last buildReport call via a thread-local
        ResumeSignals signals = lastSignals.get();
        lastSignals.remove();
        return new ReviewResult(report, resume, signals);
    }

    public record ReviewResult(FeedbackReport report, Resume resume, ResumeSignals signals) {}

    // Thread-local to pass signals out of buildReport without changing its signature
    private final ThreadLocal<ResumeSignals> lastSignals = new ThreadLocal<>();

    /** Evaluation path — skips file ingest, uses plain text. */
    public FeedbackReport reviewRawText(String resumeText, String jdText) {
        RawDocument raw = syntheticRawDocument(resumeText);
        JobDescription jd = jdParser.parse(jdText);
        Resume resume = extractionService.extract(raw);
        return buildReport(resume, jd, raw);
    }

    private FeedbackReport buildReport(Resume resume, JobDescription jd, RawDocument raw) {
        return buildReport(resume, jd, raw, false, null);
    }

    private FeedbackReport buildReport(Resume resume, JobDescription jd, RawDocument raw, boolean jdIncomplete) {
        return buildReport(resume, jd, raw, jdIncomplete, null);
    }

    private FeedbackReport buildReport(Resume resume, JobDescription jd, RawDocument raw, boolean jdIncomplete, String userId) {
        ResumeSignals signals = signalService.compute(resume, jd, raw);
        lastSignals.set(signals); // expose for reviewFull()
        CoherenceEngine.CoherenceResult coherence = coherenceEngine.check(signals);
        ClassificationEngine.ClassificationResult classification = classificationEngine.classify(signals, coherence);

        FeedbackReport.RoleContext roleContext = new FeedbackReport.RoleContext();
        roleContext.setTitle(jd.getRoleTitle());
        roleContext.setRequired(jd.getMustHaveSkills());
        roleContext.setPreferred(jd.getNiceToHaveSkills());
        roleContext.setInferred(jd.getImpliedSkills());
        roleContext.setDomain(extractionService.inferDomain(jd.getRoleTitle()));
        if (jd.getImplicitExpectations() != null && !jd.getImplicitExpectations().isEmpty()) {
            roleContext.setImplicitExpectations(jd.getImplicitExpectations());
        }
        // Populate missing required skills so the frontend can visually distinguish matched vs missing chips
        if (signals.getMustHaveResults() != null) {
            List<String> missingRequired = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING)
                .map(com.resumestudio.reviewer.model.SkillMatchResult::getJdSkill)
                .toList();
            roleContext.setMissingRequired(missingRequired);

            // Gap classification (career-ops Block B): hard blocker vs nice-to-have
            // A missing skill is a HARD BLOCKER if:
            //   - It's in the top 50% of required skills by TF-IDF weight, OR
            //   - The candidate is missing >40% of all required skills (stack gap too wide)
            long totalRequired = signals.getMustHaveResults().size();
            long missingCount = missingRequired.size();
            boolean widestackGap = totalRequired > 0 && (double) missingCount / totalRequired > 0.4;

            List<String> hardBlockers = new ArrayList<>();
            List<String> niceToHave = new ArrayList<>();
            for (String skill : missingRequired) {
                Double weight = jd.getSkillWeights().get(skill);
                boolean isHighWeight = weight != null && weight > 0.6;
                if (isHighWeight || widestackGap) hardBlockers.add(skill);
                else niceToHave.add(skill);
            }
            roleContext.setHardBlockerSkills(hardBlockers);
            roleContext.setNiceToHaveGaps(niceToHave);
        }

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
        // P19: warn when JD fetched from URL is too short to be reliable
        if (jdIncomplete) {
            redFlags.add(new FeedbackReport.RedFlag("JD_FETCH_INCOMPLETE", ImpactLevel.HIGH,
                "The job description fetched from the URL appears incomplete. For a more accurate review, paste the full JD text directly."));
        }

        ResumeScore resumeScore = scoreCalculator.calculate(signals);

        // Build recruiter simulation timeline
        java.util.List<com.resumestudio.reviewer.model.TimelineEvent> timeline =
            timelineEngine.build(signals, classification.verdict());

        // Derive momentOfDecision deterministically from the worst signal — never let AI fabricate it
        String momentOfDecision = deriveMomentOfDecision(signals);

        FeedbackReport.Builder builder = FeedbackReport.builder()
            .verdict(classification.verdict())
            .confidence(classification.confidence())
            .interviewLikelihood(classification.interviewLikelihood())
            .scanDuration(classification.scanDuration())
            .seniorityCalibration(classification.seniorityCalibration())
            // P10: single source of truth — derive from ResumeScore, not ClassificationEngine
            .tailoringScore((int) Math.round(resumeScore.getTailoringScore() / 10.0))
            .jdClarity(classification.jdClarity())
            .recruiterType(classification.recruiterType())
            .competitiveContext(classification.competitiveContext())
            .roleContext(roleContext)
            .redFlags(redFlags)
            .momentOfDecision(momentOfDecision)
            .score(resumeScore)
            .timeline(timeline);

        FeedbackReport report = aiReviewService.enrich(builder, signals, classification, jd, resume, coherence,
            (int) Math.round(resumeScore.getTailoringScore() / 10.0)).build();
        // P18: enforce deterministic fix ordering after AI enrichment
        if (report.getFixes() != null) {
            report.getFixes().sort(java.util.Comparator.comparingInt(fix -> fixPriority(fix.getSignalId())));
            for (int i = 0; i < report.getFixes().size(); i++) report.getFixes().get(i).setRank(i + 1);
        }
        outcomeTracker.track(report, signals, userId, null, null);
        return report;
    }

    /**
     * Derives the moment-of-decision from the worst signal — the first thing a recruiter
     * would notice that determines whether they continue reading.
     * Deterministic: no AI involvement.
     */
    private String deriveMomentOfDecision(ResumeSignals signals) {
        // Missing must-have skills are the #1 stop signal
        if (signals.isHasMissingMustHaves() && signals.getMustHaveResults() != null) {
            long missing = signals.getMustHaveResults().stream()
                .filter(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING).count();
            if (missing > 0) {
                String skill = signals.getMustHaveResults().stream()
                    .filter(r -> r.getVisibility() == com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING)
                    .map(com.resumestudio.reviewer.model.SkillMatchResult::getJdSkill).findFirst().orElse("a required skill");
                return "Skills section — \"" + skill + "\" not found (~6s)";
            }
        }
        // Significant YOE gap
        if (signals.getYoeFit() == com.resumestudio.reviewer.model.enums.YoeFit.UNDER_RANGE_SIGNIFICANT) {
            String yoe = signals.getCalculatedYoe() != null ? String.format("%.0f", signals.getCalculatedYoe()) : "?";
            return "Experience section — " + yoe + " yrs vs " + (signals.getJdYoeMin() != null ? signals.getJdYoeMin().intValue() + "+" : "required") + " (~10s)";
        }
        // Title mismatch
        if (signals.getTitleMatch() == com.resumestudio.reviewer.model.enums.TitleMatch.MISS) {
            return "Header — title doesn't match role (~3s)";
        }
        // Buried skills
        if (signals.isHasBuriedMustHaves()) {
            return "Skills section — required skills not visible at top (~8s)";
        }
        // No summary
        if (!signals.isSummaryPresent()) {
            return "Top of resume — no summary to frame the candidate (~5s)";
        }
        // All good
        return "Skills section — stack confirmed (~8s)";
    }

    /** P18: deterministic fix priority — lower number = higher priority. */
    private int fixPriority(String signalId) {
        if (signalId == null) return 99;
        return switch (signalId) {
            case "skill_match" -> 1;
            case "yoe_fit"     -> 2;
            case "title_match" -> 3;
            case "summary"     -> 4;
            case "bullets"     -> 5;
            default            -> 10;
        };
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
