package com.resumestudio.reviewer.ats;

import com.resumestudio.reviewer.api.RateLimiterService;
import com.resumestudio.reviewer.extraction.JdParserService;
import com.resumestudio.reviewer.extraction.ResumeExtractionService;
import com.resumestudio.reviewer.ingest.ResumeIngestService;
import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.model.Resume;
import com.resumestudio.reviewer.signals.SignalComputationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * ATS scoring endpoints.
 *
 * POST /api/ats/score          — file upload → AtsScoreResponse (full shape)
 * POST /api/ats/score-text     — JSON {resumeContent, jobDescription} → AtsScoreResponse
 * POST /api/ats/nlp-analysis   — JSON {resumeContent, jobDescription} → NlpAnalysisResponse
 * POST /api/ats/extract        — file upload → raw text
 */
@RestController
@RequestMapping("/api/ats")
public class AtsController {

    private static final Logger log = LoggerFactory.getLogger(AtsController.class);

    private final ResumeIngestService ingestService;
    private final ResumeExtractionService extractionService;
    private final AtsResponseBuilder responseBuilder;
    private final NlpAnalysisBuilder nlpBuilder;
    private final JdParserService jdParser;
    private final SignalComputationService signalService;
    private final RateLimiterService rateLimiter;

    public AtsController(ResumeIngestService ingestService,
                         ResumeExtractionService extractionService,
                         AtsResponseBuilder responseBuilder,
                         NlpAnalysisBuilder nlpBuilder,
                         JdParserService jdParser,
                         SignalComputationService signalService,
                         RateLimiterService rateLimiter) {
        this.ingestService = ingestService;
        this.extractionService = extractionService;
        this.responseBuilder = responseBuilder;
        this.nlpBuilder = nlpBuilder;
        this.jdParser = jdParser;
        this.signalService = signalService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Primary endpoint — accepts plain text, returns the full ATSScoreResponse shape.
     * Called by the Next.js /api/ats-score proxy route.
     */
    @PostMapping("/score-text")
    public ResponseEntity<?> scoreText(
        @RequestBody AtsTextRequest body,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        if (rateLimiter.isLimited(request))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Too many requests. Please wait a moment."));

        String resumeContent = body.resumeContent != null ? body.resumeContent.trim() : "";
        if (resumeContent.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "resumeContent is required."));

        try {
            // Build a lightweight Resume from plain text using the extraction pipeline
            RawDocument raw = RawDocument.fromText(resumeContent);
            Resume resume = extractionService.extract(raw);
            AtsScoreResponse response = responseBuilder.build(resume, resumeContent, body.jobDescription);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("ATS score-text failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to score resume. Please try again."));
        }
    }

    /**
     * NLP analysis endpoint — returns ATSNLPAnalysis shape.
     * Called by the Next.js /api/ats-nlp-analysis proxy route.
     */
    @PostMapping("/nlp-analysis")
    public ResponseEntity<?> nlpAnalysis(
        @RequestBody AtsTextRequest body,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        if (rateLimiter.isLimited(request))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Too many requests. Please wait a moment."));

        String resumeContent = body.resumeContent != null ? body.resumeContent.trim() : "";
        if (resumeContent.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "resumeContent is required."));
        try {
            RawDocument raw = RawDocument.fromText(resumeContent);
            Resume resume = extractionService.extract(raw);

            // If JD provided, run signal pipeline to get pre-computed SkillMatchResults
            // so NlpAnalysisBuilder can use ESCO-matched skills instead of naive string matching
            java.util.List<com.resumestudio.reviewer.model.SkillMatchResult> mustHaveResults = null;
            if (body.jobDescription != null && !body.jobDescription.isBlank()) {
                try {
                    com.resumestudio.reviewer.model.JobDescription jd = jdParser.parse(body.jobDescription);
                    com.resumestudio.reviewer.model.ResumeSignals signals = signalService.compute(resume, jd, raw);
                    mustHaveResults = signals.getMustHaveResults();
                } catch (Exception e) {
                    log.warn("Signal pipeline failed in nlp-analysis — using fallback: {}", e.getMessage());
                }
            }

            NlpAnalysisResponse response = nlpBuilder.build(resume, resumeContent, body.jobDescription, mustHaveResults);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("ATS nlp-analysis failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to run NLP analysis. Please try again."));
        }
    }

    /**
     * Legacy file-upload endpoint — kept for backward compatibility.
     */
    @PostMapping("/score")
    public ResponseEntity<?> score(
        @RequestParam("resume") MultipartFile file,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        if (rateLimiter.isLimited(request))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Too many requests. Please wait a moment."));

        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No resume file provided."));

        try {
            RawDocument raw = ingestService.ingest(file);
            Resume resume = extractionService.extract(raw);
            AtsScoreResponse response = responseBuilder.build(resume, raw.getFullText(), null);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("ATS scoring failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to score resume. Please try again."));
        }
    }

    /**
     * POST /api/ats/extract — returns raw extracted text from a resume file.
     */
    @PostMapping("/extract")
    public ResponseEntity<?> extract(
        @RequestParam("resume") MultipartFile file,
        jakarta.servlet.http.HttpServletRequest request
    ) {
        if (rateLimiter.isLimited(request))
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "Too many requests. Please wait a moment."));

        if (file == null || file.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "No resume file provided."));

        try {
            RawDocument raw = ingestService.ingest(file);
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "resume";
            return ResponseEntity.ok(new AtsExtractResponse(raw.getFullText(), filename));
        } catch (Exception e) {
            log.error("ATS extract failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to extract resume text. Please try again."));
        }
    }
}
