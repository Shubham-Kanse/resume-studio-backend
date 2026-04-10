package com.resumestudio.reviewer.ingest;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Entry point for all resume ingestion.
 * Routes to the correct extractor based on file type,
 * handles OCR fallback for scanned PDFs.
 */
@Service
public class ResumeIngestService {

    private final PdfExtractor pdfExtractor;
    private final DocxExtractor docxExtractor;
    private final OcrFallbackExtractor ocrFallbackExtractor;

    public ResumeIngestService(PdfExtractor pdfExtractor,
                                DocxExtractor docxExtractor,
                                OcrFallbackExtractor ocrFallbackExtractor) {
        this.pdfExtractor = pdfExtractor;
        this.docxExtractor = docxExtractor;
        this.ocrFallbackExtractor = ocrFallbackExtractor;
    }

    public RawDocument ingest(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        String contentType = file.getContentType();

        if (isPdf(filename, contentType)) {
            // Try native extraction first
            RawDocument rawDoc = pdfExtractor.extract(file.getInputStream(), filename);

            if (rawDoc.isScanned() || rawDoc.getParseConfidence() < 0.3) {
                // Scanned PDF — fall back to OCR
                rawDoc = ocrFallbackExtractor.extract(file.getInputStream(), filename);
            }
            return rawDoc;

        } else if (isDocx(filename, contentType)) {
            return docxExtractor.extract(file.getInputStream(), filename);

        } else {
            throw new UnsupportedFileTypeException(
                "Unsupported file type: " + filename + ". Please upload a PDF or Word document.");
        }
    }

    private boolean isPdf(String filename, String contentType) {
        return "application/pdf".equalsIgnoreCase(contentType)
            || (filename != null && filename.toLowerCase().endsWith(".pdf"));
    }

    private boolean isDocx(String filename, String contentType) {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equalsIgnoreCase(contentType)
            || (filename != null && filename.toLowerCase().endsWith(".docx"))
            || (filename != null && filename.toLowerCase().endsWith(".doc"));
    }

    public static class UnsupportedFileTypeException extends RuntimeException {
        public UnsupportedFileTypeException(String message) {
            super(message);
        }
    }
}
