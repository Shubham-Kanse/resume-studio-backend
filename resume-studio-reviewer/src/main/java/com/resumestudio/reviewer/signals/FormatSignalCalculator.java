package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.model.ResumeSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Evaluates resume layout and formatting quality.
 * Uses bounding box data where available (PDFBox), falls back to text heuristics.
 */
@Component
public class FormatSignalCalculator {

    private static final Logger log = LoggerFactory.getLogger(FormatSignalCalculator.class);

    private static final double WALL_OF_TEXT_THRESHOLD = 0.12; // whitespace ratio below this = wall of text
    private static final double MIN_FONT_SIZE = 9.0;
    private static final int MAX_DISTINCT_FONT_SIZES = 4;

    // Date format patterns — more than 2 different patterns = inconsistent
    private static final List<Pattern> DATE_PATTERNS = List.of(
        Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{4}\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\d{2}/\\d{4}"),
        Pattern.compile("\\d{4}\\s*[–\\-]\\s*\\d{4}"),
        Pattern.compile("\\b(January|February|March|April|June|July|August|September|October|November|December)\\s+\\d{4}\\b", Pattern.CASE_INSENSITIVE)
    );

    public void compute(RawDocument rawDoc, double candidateYoe, ResumeSignals signals) {
        if (rawDoc == null) return;

        String fullText = rawDoc.getFullText() != null ? rawDoc.getFullText() : "";
        int pageCount = rawDoc.getPages() != null ? rawDoc.getPages().size() : 1;

        // ── Wall of text ──────────────────────────────────────────────────
        double whitespaceRatio = computeWhitespaceRatio(fullText);
        signals.setFormatWallOfText(whitespaceRatio < WALL_OF_TEXT_THRESHOLD);

        // ── Page count vs YOE ─────────────────────────────────────────────
        // Rule: 1 page per 5 years of experience; max 2 pages under 8 years
        boolean tooManyPages = false;
        if (candidateYoe < 8 && pageCount > 2) tooManyPages = true;
        if (candidateYoe < 3 && pageCount > 1) tooManyPages = true;
        signals.setFormatTooManyPages(tooManyPages);

        // ── Font size (from blocks if available) ──────────────────────────
        if (rawDoc.getPages() != null) {
            OptionalDouble minFont = rawDoc.getPages().stream()
                .flatMap(p -> p.getBlocks() != null ? p.getBlocks().stream() : java.util.stream.Stream.empty())
                .mapToDouble(RawDocument.RawTextBlock::getFontSize)
                .filter(f -> f > 0)
                .min();

            Set<Double> fontSizes = new TreeSet<>();
            rawDoc.getPages().forEach(p -> {
                if (p.getBlocks() != null) p.getBlocks().forEach(b -> {
                    if (b.getFontSize() > 0) fontSizes.add((double) Math.round(b.getFontSize()));
                });
            });

            signals.setFormatFontTooSmall(minFont.isPresent() && minFont.getAsDouble() < MIN_FONT_SIZE);
            signals.setFormatMixedFonts(fontSizes.size() > MAX_DISTINCT_FONT_SIZES);
        }

        // ── Multi-column layout ───────────────────────────────────────────
        signals.setFormatIsMultiColumn(detectMultiColumn(rawDoc));

        // ── Photo ─────────────────────────────────────────────────────────
        signals.setFormatHasPhoto(rawDoc.getPages() != null
            && !rawDoc.getPages().isEmpty()
            && detectPhoto(rawDoc));

        // ── Inconsistent date formats ─────────────────────────────────────
        signals.setFormatInconsistentDates(detectInconsistentDates(fullText));
    }

    private double computeWhitespaceRatio(String text) {
        if (text == null || text.isBlank()) return 0.5;
        long totalChars = text.length();
        long whitespaceChars = text.chars().filter(c -> c == '\n' || c == '\r' || c == ' ').count();
        return (double) whitespaceChars / totalChars;
    }

    private boolean detectMultiColumn(RawDocument rawDoc) {
        if (rawDoc.getPages() == null || rawDoc.getPages().isEmpty()) return false;
        RawDocument.RawPage firstPage = rawDoc.getPages().get(0);
        if (firstPage.getBlocks() == null || firstPage.getBlocks().size() < 10) return false;

        // Multi-column heuristic: if many text blocks have X positions clustered into 2+ groups
        // with a significant gap between them, it's likely multi-column
        List<Float> xPositions = firstPage.getBlocks().stream()
            .map(RawDocument.RawTextBlock::getX)
            .filter(x -> x > 5)
            .toList();

        if (xPositions.isEmpty()) return false;

        float maxX = xPositions.stream().max(Float::compare).orElse(0f);
        long leftColumn = xPositions.stream().filter(x -> x < maxX * 0.4f).count();
        long rightColumn = xPositions.stream().filter(x -> x > maxX * 0.5f).count();

        // If significant blocks exist in both left and right zones = multi-column
        return leftColumn > 5 && rightColumn > 5;
    }

    private boolean detectPhoto(RawDocument rawDoc) {
        // Photo was signalled during PDF extraction
        // We check a heuristic: first block in header zone is blank but has image metadata
        if (rawDoc.getPages() == null || rawDoc.getPages().isEmpty()) return false;
        RawDocument.RawPage firstPage = rawDoc.getPages().get(0);
        if (firstPage.getBlocks() == null || firstPage.getBlocks().isEmpty()) return false;
        RawDocument.RawTextBlock firstBlock = firstPage.getBlocks().get(0);
        return firstBlock.isBold() && (firstBlock.getText() == null || firstBlock.getText().isBlank());
    }

    private boolean detectInconsistentDates(String text) {
        int formatsFound = 0;
        for (Pattern pattern : DATE_PATTERNS) {
            if (pattern.matcher(text).find()) formatsFound++;
        }
        // More than 2 different date formats in a single document = inconsistent
        return formatsFound > 2;
    }
}
