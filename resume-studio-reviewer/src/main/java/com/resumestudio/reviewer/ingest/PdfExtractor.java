package com.resumestudio.reviewer.ingest;

import com.resumestudio.reviewer.model.enums.ParseSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Extracts text and layout metadata from text-based PDFs using Apache PDFBox 3.x.
 * Captures font sizes and bounding boxes for layout analysis.
 * Falls back to OcrFallbackExtractor if text content is below threshold.
 */
@Component
public class PdfExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractor.class);

    @Value("${reviewer.ingest.min-text-length:100}")
    private int minTextLengthForNative; // Fix #7: Configurable threshold

    @Value("${reviewer.ingest.parse-confidence-threshold:0.3}")
    private double parseConfidenceThreshold;

    public RawDocument extract(InputStream inputStream, String filename) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {

            if (document.isEncrypted()) {
                // Fix: Set all required fields for encrypted PDFs
                RawDocument doc = new RawDocument();
                doc.setPasswordProtected(true);
                doc.setFilename(filename);
                doc.setParseConfidence(0.0);
                doc.setMimeType("application/pdf");
                doc.setSource(ParseSource.PDF_TEXT);
                doc.setScanned(false);
                doc.setFullText("");
                doc.setPages(List.of());
                return doc;
            }
            
            // Fix: Handle zero-page PDFs
            if (document.getNumberOfPages() == 0) {
                RawDocument doc = new RawDocument();
                doc.setFilename(filename);
                doc.setMimeType("application/pdf");
                doc.setSource(ParseSource.PDF_TEXT);
                doc.setScanned(false);
                doc.setFullText("");
                doc.setPages(List.of());
                doc.setParseConfidence(0.0);
                return doc;
            }

            // Extract raw text first to check if this is a scanned PDF
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(document);

            RawDocument rawDoc = new RawDocument();
            rawDoc.setFilename(filename);
            rawDoc.setMimeType("application/pdf");

            if (fullText.trim().length() < minTextLengthForNative) {
                // Likely scanned — signal for OCR fallback
                rawDoc.setScanned(true);
                rawDoc.setParseConfidence(0.0);
                rawDoc.setSource(ParseSource.PDF_OCR);
                rawDoc.setFullText(""); // Fix: Set empty text
                rawDoc.setPages(List.of()); // Fix: Set empty pages list
                return rawDoc;
            }

            rawDoc.setFullText(fullText);
            rawDoc.setScanned(false);
            rawDoc.setSource(ParseSource.PDF_TEXT);

            // Extract per-page blocks with font metadata
            List<RawDocument.RawPage> pages = new ArrayList<>();
            LayoutAwareStripper layoutStripper = new LayoutAwareStripper();

            for (int i = 1; i <= document.getNumberOfPages(); i++) {
                layoutStripper.setStartPage(i);
                layoutStripper.setEndPage(i);
                layoutStripper.getText(document);  // triggers block collection

                RawDocument.RawPage page = new RawDocument.RawPage();
                page.setPageNumber(i);
                page.setText(layoutStripper.getPageText());
                page.setBlocks(layoutStripper.getAndResetBlocks());
                pages.add(page);
            }

            rawDoc.setPages(pages);

            // Fix #4: Detect photos properly and store in dedicated field
            boolean hasPhoto = detectPhotos(document);
            rawDoc.setHasPhoto(hasPhoto);
            rawDoc.setParseConfidence(computeConfidence(fullText, pages));

            return rawDoc;
        }
    }

    // Fix #4: Improved photo detection across all pages
    private boolean detectPhotos(PDDocument document) {
        try {
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                PDPage page = document.getPage(i);
                for (var name : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(name) instanceof PDImageXObject) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Photo detection failed: {}", e.getMessage());
        }
        return false;
    }

    private double computeConfidence(String text, List<RawDocument.RawPage> pages) {
        if (text == null || text.trim().isEmpty()) return 0.0;
        // More pages + more text blocks = higher confidence
        double textScore = Math.min(1.0, text.length() / 2000.0);
        double pageScore = pages.size() > 0 ? 1.0 : 0.5;
        return (textScore * 0.7) + (pageScore * 0.3);
    }

    /**
     * PDFBox stripper subclass that collects font-aware text blocks.
     */
    private static class LayoutAwareStripper extends PDFTextStripper {

        private final List<RawDocument.RawTextBlock> blocks = new ArrayList<>();
        private final StringBuilder pageTextBuilder = new StringBuilder(); // Fix #2

        public LayoutAwareStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (positions == null || positions.isEmpty()) return;

            TextPosition first = positions.get(0);
            RawDocument.RawTextBlock block = new RawDocument.RawTextBlock();
            block.setText(text);
            block.setX(first.getX());
            block.setY(first.getY());
            block.setFontSize(first.getFontSize());
            
            // Fix: Null-safe font name check
            String fontName = first.getFont() != null ? first.getFont().getName() : null;
            block.setBold(fontName != null && fontName.toLowerCase().contains("bold"));
            blocks.add(block);

            pageTextBuilder.append(text); // Fix #2: Accumulate page text
            super.writeString(text, positions);
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            pageTextBuilder.setLength(0); // Fix #2: Reset for new page
            super.startPage(page);
        }

        public List<RawDocument.RawTextBlock> getAndResetBlocks() {
            List<RawDocument.RawTextBlock> copy = new ArrayList<>(blocks);
            blocks.clear();
            return copy;
        }

        public String getPageText() { 
            return pageTextBuilder.toString(); // Fix #2: Return accumulated text
        }
    }
}
