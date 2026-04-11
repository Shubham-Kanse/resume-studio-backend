package com.resumestudio.reviewer.ingest;

import com.resumestudio.reviewer.model.enums.ParseSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * OCR fallback for scanned PDFs using Tess4J (Tesseract JNI wrapper).
 * Used when PdfExtractor detects insufficient extractable text.
 *
 * Requires Tesseract to be installed on the host:
 *   apt-get install tesseract-ocr
 *   or set TESSDATA_PREFIX env variable pointing to tessdata directory.
 */
@Component
public class OcrFallbackExtractor {

    private static final Logger log = LoggerFactory.getLogger(OcrFallbackExtractor.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(4); // Fix: Instance-level, not static

    @Value("${screener.tesseract.datapath:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessDataPath;

    @Value("${screener.tesseract.psm:6}") // Fix #9: Configurable PSM mode (6 = uniform block)
    private int pageSegMode;

    public RawDocument extract(InputStream inputStream, String filename) throws IOException {
        PDDocument document = PDDocument.load(inputStream);
        
        try {
            int numPages = document.getNumberOfPages();
            
            // Fix: Handle zero-page PDFs
            if (numPages == 0) {
                RawDocument doc = new RawDocument();
                doc.setFilename(filename);
                doc.setMimeType("application/pdf");
                doc.setSource(ParseSource.PDF_OCR);
                doc.setScanned(false);
                doc.setFullText("");
                doc.setPages(List.of());
                doc.setParseConfidence(0.0);
                return doc;
            }

            // Fix #5: Parallel page rendering and OCR
            List<Future<PageOcrResult>> futures = new ArrayList<>();
            for (int i = 0; i < numPages; i++) {
                final int pageIndex = i;
                // Fix: Pass document, create PDFRenderer per thread (not thread-safe)
                futures.add(executor.submit(() -> {
                    PDFRenderer renderer = new PDFRenderer(document);
                    return processPage(renderer, createTesseract(), pageIndex);
                }));
            }

            StringBuilder fullText = new StringBuilder();
            List<RawDocument.RawPage> pages = new ArrayList<>();

            for (int i = 0; i < numPages; i++) {
                try {
                    // Fix: Add timeout to prevent hung OCR
                    PageOcrResult result = futures.get(i).get(30, TimeUnit.SECONDS);
                    fullText.append(result.text).append("\n");
                    pages.add(result.page);
                } catch (TimeoutException e) {
                    log.error("OCR timeout for page {}", i + 1);
                    futures.get(i).cancel(true); // Cancel hung task
                    // Add empty page on failure
                    RawDocument.RawPage page = new RawDocument.RawPage();
                    page.setPageNumber(i + 1);
                    page.setText("");
                    page.setBlocks(List.of());
                    pages.add(page);
                } catch (InterruptedException e) {
                    log.error("OCR interrupted for page {}", i + 1);
                    Thread.currentThread().interrupt(); // Fix: Restore interrupt flag
                    // Add empty page on failure
                    RawDocument.RawPage page = new RawDocument.RawPage();
                    page.setPageNumber(i + 1);
                    page.setText("");
                    page.setBlocks(List.of());
                    pages.add(page);
                } catch (ExecutionException e) {
                    log.error("OCR failed for page {}: {}", i + 1, e.getMessage());
                    // Add empty page on failure
                    RawDocument.RawPage page = new RawDocument.RawPage();
                    page.setPageNumber(i + 1);
                    page.setText("");
                    page.setBlocks(List.of());
                    pages.add(page);
                }
            }

            RawDocument rawDoc = new RawDocument();
            rawDoc.setFilename(filename);
            rawDoc.setMimeType("application/pdf"); // Fix: Set MIME type
            rawDoc.setFullText(fullText.toString());
            rawDoc.setPages(pages);
            rawDoc.setSource(ParseSource.PDF_OCR);
            rawDoc.setScanned(true);
            
            // Fix #10: Validate OCR quality
            double confidence = computeOcrConfidence(fullText.toString());
            rawDoc.setParseConfidence(confidence);

            return rawDoc;
            
        } finally {
            // Fix: Close document after all parallel tasks complete
            try {
                document.close();
            } catch (IOException e) {
                log.warn("Failed to close document: {}", e.getMessage());
            }
        }
    }

    private Tesseract createTesseract() {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessDataPath);
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(1);
        tesseract.setPageSegMode(pageSegMode);
        return tesseract;
    }

    // Fix #3 & #5: Extract blocks with position data from OCR
    private PageOcrResult processPage(PDFRenderer renderer, Tesseract tesseract, int pageIndex) throws IOException {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300, ImageType.RGB);

        String pageText = "";
        List<RawDocument.RawTextBlock> blocks = new ArrayList<>();

        try {
            // Fix #3: Use getWords() to get bounding box data
            List<Word> words = tesseract.getWords(image, 0);
            if (words == null) {
                words = List.of(); // Fix: Handle null return
            }
            StringBuilder textBuilder = new StringBuilder();

            for (Word word : words) {
                // Fix: Null safety checks
                if (word == null || word.getText() == null || word.getBoundingBox() == null) {
                    continue;
                }
                
                RawDocument.RawTextBlock block = new RawDocument.RawTextBlock();
                block.setText(word.getText());
                block.setX(word.getBoundingBox().x);
                block.setY(word.getBoundingBox().y);
                block.setWidth(word.getBoundingBox().width);
                block.setHeight(word.getBoundingBox().height);
                block.setFontSize(word.getBoundingBox().height * 0.75f); // Estimate font size
                block.setBold(false); // OCR can't detect bold reliably
                block.setPageNumber(pageIndex + 1);
                blocks.add(block);

                textBuilder.append(word.getText()).append(" ");
            }
            pageText = textBuilder.toString();

        } catch (Exception e) {
            log.warn("OCR failed for page {}: {}", pageIndex + 1, e.getMessage());
            pageText = "";
        }

        RawDocument.RawPage page = new RawDocument.RawPage();
        page.setPageNumber(pageIndex + 1);
        page.setText(pageText);
        page.setBlocks(blocks);

        return new PageOcrResult(pageText, page);
    }

    /**
     * Fix #10: Enhanced OCR quality validation with language detection heuristic
     */
    private double computeOcrConfidence(String text) {
        if (text == null || text.isBlank()) return 0.0;
        
        long totalChars = text.length();
        long normalChars = text.chars()
            .filter(c -> (c >= 32 && c <= 126) || c == '\n' || c == '\t')
            .count();
        
        // Check for common English words as quality signal
        String lowerText = text.toLowerCase();
        String[] commonWords = {"the", "and", "to", "of", "a", "in", "for", "is", "on", "that"};
        long wordMatches = 0;
        for (String word : commonWords) {
            if (lowerText.contains(" " + word + " ")) wordMatches++;
        }
        
        double charRatio = (double) normalChars / totalChars;
        double wordBonus = Math.min(0.2, wordMatches * 0.02); // Up to +0.2 for word matches
        
        return Math.max(0.0, Math.min(1.0, charRatio - 0.1 + wordBonus));
    }

    private static class PageOcrResult {
        final String text;
        final RawDocument.RawPage page;

        PageOcrResult(String text, RawDocument.RawPage page) {
            this.text = text;
            this.page = page;
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
