package com.resumestudio.reviewer.ingest;

import com.resumestudio.reviewer.model.enums.ParseSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
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

    private static final int MIN_TEXT_LENGTH_FOR_NATIVE = 100;

    public RawDocument extract(InputStream inputStream, String filename) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {

            if (document.isEncrypted()) {
                RawDocument doc = new RawDocument();
                doc.setPasswordProtected(true);
                doc.setFilename(filename);
                doc.setParseConfidence(0.0);
                return doc;
            }

            // Extract raw text first to check if this is a scanned PDF
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(document);

            RawDocument rawDoc = new RawDocument();
            rawDoc.setFilename(filename);
            rawDoc.setMimeType("application/pdf");

            if (fullText.trim().length() < MIN_TEXT_LENGTH_FOR_NATIVE) {
                // Likely scanned — signal for OCR fallback
                rawDoc.setScanned(true);
                rawDoc.setParseConfidence(0.0);
                rawDoc.setSource(ParseSource.PDF_OCR);
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

            // Detect photos (image objects in the PDF)
            boolean hasPhoto = detectPhoto(document);
            rawDoc.setParseConfidence(computeConfidence(fullText, pages));

            // Store photo flag in a field we carry through
            // (we set this on Resume later in HeaderExtractor)
            rawDoc.getPages().get(0).getBlocks().stream().findFirst()
                .ifPresent(b -> b.setBold(hasPhoto)); // temp reuse — refactored in Resume

            return rawDoc;
        }
    }

    private boolean detectPhoto(PDDocument document) {
        try {
            PDPage firstPage = document.getPage(0);
            return firstPage.getResources().getXObjectNames().iterator().hasNext()
                && firstPage.getResources().getXObject(
                    firstPage.getResources().getXObjectNames().iterator().next()
                ) instanceof PDImageXObject;
        } catch (Exception e) {
            return false;
        }
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
        private String pageText = "";

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
            block.setBold(first.getFont().getName() != null
                && first.getFont().getName().toLowerCase().contains("bold"));
            blocks.add(block);

            super.writeString(text, positions);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            pageText = getText(getCurrentPage() != null ? new PDDocument() : new PDDocument());
            super.endPage(page);
        }

        public List<RawDocument.RawTextBlock> getAndResetBlocks() {
            List<RawDocument.RawTextBlock> copy = new ArrayList<>(blocks);
            blocks.clear();
            return copy;
        }

        public String getPageText() { return pageText; }
    }
}
