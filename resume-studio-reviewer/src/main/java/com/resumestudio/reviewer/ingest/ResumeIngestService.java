package com.resumestudio.reviewer.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Entry point for all resume ingestion.
 * Routes to the correct extractor based on file type,
 * handles OCR fallback for scanned PDFs.
 */
@Service
public class ResumeIngestService {

    private static final Logger log = LoggerFactory.getLogger(ResumeIngestService.class);

    private final PdfExtractor pdfExtractor;
    private final DocxExtractor docxExtractor;
    private final OcrFallbackExtractor ocrFallbackExtractor;
    private final DocumentCache documentCache; // Fix #6

    @Value("${reviewer.ingest.parse-confidence-threshold:0.3}")
    private double parseConfidenceThreshold; // Fix #7: Use configurable threshold

    public ResumeIngestService(PdfExtractor pdfExtractor,
                                DocxExtractor docxExtractor,
                                OcrFallbackExtractor ocrFallbackExtractor,
                                DocumentCache documentCache) {
        this.pdfExtractor = pdfExtractor;
        this.docxExtractor = docxExtractor;
        this.ocrFallbackExtractor = ocrFallbackExtractor;
        this.documentCache = documentCache;
    }

    public RawDocument ingest(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            filename = "unknown"; // Fix: Handle null filename
        }
        
        String contentType = file.getContentType();
        byte[] fileBytes = file.getBytes();
        
        // Fix: Validate file size
        if (fileBytes.length == 0) {
            throw new UnsupportedFileTypeException("Empty file uploaded");
        }
        if (fileBytes.length > 5 * 1024 * 1024) { // 5MB limit
            throw new UnsupportedFileTypeException("File too large. Maximum size is 5MB");
        }

        // Fix #6: Check cache first
        RawDocument cached = documentCache.get(fileBytes);
        if (cached != null) {
            log.debug("Cache hit for file: {}", filename);
            return cached;
        }

        RawDocument rawDoc;

        if (isPdf(filename, contentType)) {
            // Try native extraction first
            rawDoc = pdfExtractor.extract(new ByteArrayInputStream(fileBytes), filename);

            if (rawDoc.isScanned() || rawDoc.getParseConfidence() < parseConfidenceThreshold) {
                // Scanned PDF — fall back to OCR
                rawDoc = ocrFallbackExtractor.extract(new ByteArrayInputStream(fileBytes), filename);
            }

        } else if (isDocx(filename, contentType)) {
            rawDoc = docxExtractor.extract(new ByteArrayInputStream(fileBytes), filename);

        } else if (filename.toLowerCase().endsWith(".doc")) {
            throw new UnsupportedFileTypeException(
                "Legacy .doc format is not supported. Please save as .docx and re-upload.");
        } else {
            throw new UnsupportedFileTypeException(
                "Unsupported file type: " + filename + ". Please upload a PDF or Word document.");
        }

        // Fix #6: Cache the result
        documentCache.put(fileBytes, rawDoc);

        // Cover letter detection
        if (isLikelyCoverLetter(rawDoc.getFullText())) {
            throw new UnsupportedFileTypeException(
                "This looks like a cover letter, not a resume. Please upload your resume (CV) instead.");
        }

        return rawDoc;
    }

    private boolean isLikelyCoverLetter(String text) {
        if (text == null || text.length() < 50) return false;
        String lower = text.toLowerCase();
        // Cover letters typically start with "Dear" or "To whom" and have no skills/experience sections
        boolean hasCoverLetterOpening = lower.contains("dear hiring") || lower.contains("dear recruiter")
            || lower.contains("to whom it may concern") || lower.contains("i am writing to")
            || lower.contains("i am applying for") || lower.contains("i would like to apply");
        boolean hasNoExperienceSection = !lower.contains("experience") && !lower.contains("work history")
            && !lower.contains("employment");
        return hasCoverLetterOpening && hasNoExperienceSection;
    }

    private boolean isPdf(String filename, String contentType) {
        return "application/pdf".equalsIgnoreCase(contentType)
            || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }

    private boolean isDocx(String filename, String contentType) {
        // Note: .doc (legacy Word 97-2003) is not supported, only .docx
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(contentType)
            || (filename != null && filename.toLowerCase().endsWith(".docx"));
    }

    public static class UnsupportedFileTypeException extends RuntimeException {
        public UnsupportedFileTypeException(String message) {
            super(message);
        }
    }
}
