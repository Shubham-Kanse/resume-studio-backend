package com.resumestudio.reviewer.generate;

import com.resumestudio.reviewer.api.JdFetchService;
import com.resumestudio.reviewer.extraction.JdParserService;
import com.resumestudio.reviewer.extraction.ResumeExtractionService;
import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.ingest.ResumeIngestService;
import com.resumestudio.reviewer.model.JobDescription;
import com.resumestudio.reviewer.model.Resume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full /generate pipeline:
 *
 *  1. Ingest each uploaded resume (PDF/DOCX → RawDocument)
 *  2. Extract structured data from each (RawDocument → Resume)
 *  3. Merge up to 3 resumes into one canonical Resume (best-data-wins)
 *  4. Parse the job description text into a structured JobDescription
 *  5. Optimize content via LLM against the JD (Resume + JD → GeneratedResumeContent)
 *  6. Build an ATS-perfect .docx file (GeneratedResumeContent → byte[])
 *
 * Returns the raw bytes of the .docx and the intended download filename.
 */
@Service
public class ResumeGenerateService {

    private static final Logger log = LoggerFactory.getLogger(ResumeGenerateService.class);

    private final ResumeIngestService ingestService;
    private final ResumeExtractionService extractionService;
    private final JdParserService jdParserService;
    private final JdFetchService jdFetchService;
    private final ResumeMergeService mergeService;
    private final ResumeContentOptimizer optimizer;
    private final DocxBuilder docxBuilder;

    public ResumeGenerateService(ResumeIngestService ingestService,
                                  ResumeExtractionService extractionService,
                                  JdParserService jdParserService,
                                  JdFetchService jdFetchService,
                                  ResumeMergeService mergeService,
                                  ResumeContentOptimizer optimizer,
                                  DocxBuilder docxBuilder) {
        this.ingestService = ingestService;
        this.extractionService = extractionService;
        this.jdParserService = jdParserService;
        this.jdFetchService = jdFetchService;
        this.mergeService = mergeService;
        this.optimizer = optimizer;
        this.docxBuilder = docxBuilder;
    }

    public record GenerateResult(byte[] docxBytes, String filename, String generationMode) {}

    /**
     * Run the full generation pipeline.
     *
     * @param resumeFiles 1–3 resume files (PDF or DOCX)
     * @param jobDescriptionInput raw JD text or a URL to fetch
     */
    public GenerateResult generate(List<MultipartFile> resumeFiles, String jobDescriptionInput)
            throws IOException, Exception {

        // ── Step 1: Resolve JD text (URL or raw paste) ───────────────────────
        String jdText = resolveJd(jobDescriptionInput);

        // ── Step 2: Ingest + extract all resume files ─────────────────────────
        List<Resume> parsedResumes = new ArrayList<>();
        for (MultipartFile file : resumeFiles) {
            log.info("Ingesting resume: {}", file.getOriginalFilename());
            RawDocument raw = ingestService.ingest(file);
            Resume resume = extractionService.extract(raw);
            parsedResumes.add(resume);
            log.info("Extracted: name={}, roles={}, skills={}",
                resume.getCandidateName(),
                resume.getExperience().size(),
                resume.getSkills().size());
        }

        // ── Step 3: Merge resumes into canonical master ───────────────────────
        Resume merged = mergeService.merge(parsedResumes);

        // ── Step 4: Parse the job description ─────────────────────────────────
        JobDescription jd = jdParserService.parse(jdText);
        log.info("Parsed JD: title={}, mustHaves={}, niceToHave={}",
            jd.getRoleTitle(), jd.getMustHaveSkills().size(), jd.getNiceToHaveSkills().size());

        // ── Step 5: LLM content optimization ─────────────────────────────────
        GeneratedResumeContent optimized = optimizer.optimize(merged, jd);

        // ── Step 6: Build the ATS-perfect .docx ──────────────────────────────
        byte[] docx = docxBuilder.build(optimized);
        String filename = docxBuilder.buildFilename(optimized.getCandidateName());
        String mode = optimized.getGenerationMode() != null
            ? optimized.getGenerationMode().name().toLowerCase()
            : "optimized";

        log.info("Generated '{}' ({} bytes) for JD role: {} [mode={}]",
            filename, docx.length, jd.getRoleTitle(), mode);
        return new GenerateResult(docx, filename, mode);
    }

    // ── JD resolution ─────────────────────────────────────────────────────────

    private String resolveJd(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Job description is required.");
        }
        String trimmed = input.trim();
        // Attempt URL fetch — JdFetchService handles the URL check internally
        try {
            return jdFetchService.resolve(trimmed);
        } catch (Exception e) {
            // Not a URL or fetch failed — use the raw text
            return trimmed;
        }
    }
}
