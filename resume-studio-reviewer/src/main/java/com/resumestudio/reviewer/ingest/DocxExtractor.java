package com.resumestudio.reviewer.ingest;


import com.resumestudio.reviewer.model.enums.ParseSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts text and basic layout metadata from .docx files using Apache POI.
 */
@Component
public class DocxExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocxExtractor.class);
    private static final float ESTIMATED_PAGE_HEIGHT = 792f; // Fix #8: Estimate page breaks (11" at 72 DPI)

    public RawDocument extract(InputStream inputStream, String filename) throws IOException {
        try (XWPFDocument document = new XWPFDocument(inputStream)) {

            RawDocument rawDoc = new RawDocument();
            rawDoc.setFilename(filename);
            rawDoc.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            rawDoc.setSource(ParseSource.DOCX);
            rawDoc.setScanned(false);

            StringBuilder fullText = new StringBuilder();
            List<RawDocument.RawTextBlock> allBlocks = new ArrayList<>();

            float yPosition = 0f;
            int currentPage = 1;

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String paraText = paragraph.getText();
                if (paraText == null || paraText.isBlank()) {
                    yPosition += 12f; // blank line spacing
                    continue;
                }

                // Detect heading style for section classification
                boolean isHeading = paragraph.getStyle() != null
                    && paragraph.getStyle().toLowerCase().contains("heading");

                // Collect font size from first run
                float fontSize = 11f;
                boolean isBold = false;
                for (XWPFRun run : paragraph.getRuns()) {
                    if (run.getFontSize() > 0) {
                        fontSize = (float) run.getFontSize();
                    }
                    if (run.isBold()) isBold = true;
                    break;
                }

                // Fix #8: Estimate page breaks based on Y position
                if (yPosition > ESTIMATED_PAGE_HEIGHT) {
                    currentPage++;
                    yPosition = 0f;
                }

                RawDocument.RawTextBlock block = new RawDocument.RawTextBlock();
                block.setText(paraText);
                block.setY(yPosition);
                block.setX(0f);
                block.setFontSize(fontSize);
                block.setBold(isBold || isHeading);
                block.setPageNumber(currentPage);
                allBlocks.add(block);

                fullText.append(paraText).append("\n");
                yPosition += fontSize + 4f;
            }

            // Fix #8: Split blocks into pages
            List<RawDocument.RawPage> pages = new ArrayList<>();
            for (int pageNum = 1; pageNum <= currentPage; pageNum++) {
                final int page = pageNum;
                List<RawDocument.RawTextBlock> pageBlocks = allBlocks.stream()
                    .filter(b -> b.getPageNumber() == page)
                    .toList();

                StringBuilder pageText = new StringBuilder();
                pageBlocks.forEach(b -> pageText.append(b.getText()).append("\n"));

                RawDocument.RawPage rawPage = new RawDocument.RawPage();
                rawPage.setPageNumber(page);
                rawPage.setText(pageText.toString());
                rawPage.setBlocks(pageBlocks);
                pages.add(rawPage);
            }

            rawDoc.setFullText(fullText.toString());
            rawDoc.setPages(pages);
            rawDoc.setParseConfidence(fullText.length() > 200 ? 0.85 : 0.5);

            return rawDoc;
        }
    }
}
