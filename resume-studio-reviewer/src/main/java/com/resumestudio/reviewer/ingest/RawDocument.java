package com.resumestudio.reviewer.ingest;

import com.resumestudio.reviewer.model.enums.ParseSource;

import java.util.List;

/**
 * Raw extracted content from a resume file.
 * Produced by the ingest layer before any NLP or structure recovery.
 */
public class RawDocument {

    private String fullText;                    // complete text of the document
    private List<RawPage> pages;                // per-page text and metadata
    private ParseSource source;
    private double parseConfidence;             // 0.0–1.0; lower for OCR
    private String filename;
    private boolean isScanned;
    private boolean isPasswordProtected;
    private String mimeType;
    private boolean hasPhoto;                   // photo detected in document

    public static class RawPage {
        private int pageNumber;
        private String text;
        private List<RawTextBlock> blocks;       // font + position aware blocks

        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public List<RawTextBlock> getBlocks() { return blocks; }
        public void setBlocks(List<RawTextBlock> blocks) { this.blocks = blocks; }
    }

    public static class RawTextBlock {
        private String text;
        private float x, y, width, height;      // bounding box
        private float fontSize;
        private boolean isBold;
        private int pageNumber;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public float getX() { return x; }
        public void setX(float x) { this.x = x; }
        public float getY() { return y; }
        public void setY(float y) { this.y = y; }
        public float getWidth() { return width; }
        public void setWidth(float width) { this.width = width; }
        public float getHeight() { return height; }
        public void setHeight(float height) { this.height = height; }
        public float getFontSize() { return fontSize; }
        public void setFontSize(float fontSize) { this.fontSize = fontSize; }
        public boolean isBold() { return isBold; }
        public void setBold(boolean bold) { isBold = bold; }
        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
    }

    public String getFullText() { return fullText; }
    public void setFullText(String fullText) { this.fullText = fullText; }

    public List<RawPage> getPages() { return pages; }
    public void setPages(List<RawPage> pages) { this.pages = pages; }

    public ParseSource getSource() { return source; }
    public void setSource(ParseSource source) { this.source = source; }

    public double getParseConfidence() { return parseConfidence; }
    public void setParseConfidence(double parseConfidence) { this.parseConfidence = parseConfidence; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public boolean isScanned() { return isScanned; }
    public void setScanned(boolean scanned) { isScanned = scanned; }

    public boolean isPasswordProtected() { return isPasswordProtected; }
    public void setPasswordProtected(boolean passwordProtected) { isPasswordProtected = passwordProtected; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public boolean hasPhoto() { return hasPhoto; }
    public void setHasPhoto(boolean hasPhoto) { this.hasPhoto = hasPhoto; }

    /**
     * Creates a RawDocument directly from plain text (no file parsing needed).
     * Used by the /api/ats/score-text and /api/ats/nlp-analysis endpoints.
     */
    public static RawDocument fromText(String text) {
        RawDocument doc = new RawDocument();
        doc.setFullText(text != null ? text : "");
        doc.setFilename("text-input");
        doc.setParseConfidence(0.9);
        doc.setScanned(false);
        doc.setSource(com.resumestudio.reviewer.model.enums.ParseSource.PDF_TEXT);
        RawPage page = new RawPage();
        page.setPageNumber(1);
        page.setText(text != null ? text : "");
        doc.setPages(List.of(page));
        return doc;
    }

    /** Returns text from the top 20% of page 1 — the header zone. */
    public String getHeaderZoneText() {
        if (pages == null || pages.isEmpty()) return "";
        RawPage firstPage = pages.get(0);
        if (firstPage.getBlocks() == null || firstPage.getBlocks().isEmpty()) {
            String text = firstPage.getText();
            return text != null ? text : ""; // Fix: Null-safe
        }

        // Estimate page height from max Y coordinate
        float maxY = firstPage.getBlocks().stream()
            .map(RawTextBlock::getY)
            .max(Float::compare)
            .orElse(800f);

        float threshold = maxY * 0.25f;  // top 25%
        StringBuilder sb = new StringBuilder();
        firstPage.getBlocks().stream()
            .filter(b -> b.getY() <= threshold)
            .forEach(b -> sb.append(b.getText()).append("\n"));
        return sb.toString();
    }
}
