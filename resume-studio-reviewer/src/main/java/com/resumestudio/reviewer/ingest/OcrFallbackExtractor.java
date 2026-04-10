package com.resumestudio.reviewer.ingest;

import com.resumestudio.reviewer.model.enums.ParseSource;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
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

    @Value("${screener.tesseract.datapath:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessDataPath;

    public RawDocument extract(InputStream inputStream, String filename) throws IOException {
        try (PDDocument document = PDDocument.load(inputStream)) {

            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tessDataPath);
            tesseract.setLanguage("eng");
            tesseract.setOcrEngineMode(1);   // LSTM engine
            tesseract.setPageSegMode(3);     // fully automatic page segmentation

            PDFRenderer renderer = new PDFRenderer(document);
            StringBuilder fullText = new StringBuilder();
            List<RawDocument.RawPage> pages = new ArrayList<>();

            for (int i = 0; i < document.getNumberOfPages(); i++) {
                // Render at 300 DPI for good OCR accuracy
                BufferedImage image = renderer.renderImageWithDPI(i, 300, ImageType.RGB);

                String pageText;
                try {
                    pageText = tesseract.doOCR(image);
                } catch (TesseractException e) {
                    pageText = "";
                }

                fullText.append(pageText).append("\n");

                RawDocument.RawPage page = new RawDocument.RawPage();
                page.setPageNumber(i + 1);
                page.setText(pageText);
                page.setBlocks(List.of()); // No block-level data from OCR
                pages.add(page);
            }

            RawDocument rawDoc = new RawDocument();
            rawDoc.setFilename(filename);
            rawDoc.setFullText(fullText.toString());
            rawDoc.setPages(pages);
            rawDoc.setSource(ParseSource.PDF_OCR);
            rawDoc.setScanned(true);
            rawDoc.setParseConfidence(computeOcrConfidence(fullText.toString()));

            return rawDoc;
        }
    }

    /**
     * Estimates OCR quality from character distribution.
     * High ratio of non-ASCII or special chars = poor OCR.
     */
    private double computeOcrConfidence(String text) {
        if (text == null || text.isBlank()) return 0.0;
        long totalChars = text.length();
        long normalChars = text.chars()
            .filter(c -> (c >= 32 && c <= 126) || c == '\n' || c == '\t')
            .count();
        double ratio = (double) normalChars / totalChars;
        return Math.max(0.0, Math.min(1.0, ratio - 0.1)); // slight penalty for OCR
    }
}
