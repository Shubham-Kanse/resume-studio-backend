package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.ingest.RawDocument;
import com.resumestudio.reviewer.model.ResumeSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FormatSignalCalculatorTest {

    private FormatSignalCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FormatSignalCalculator();
    }

    // ── null guard ────────────────────────────────────────────────────────────

    @Test
    void compute_null_doesNothing() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute(null, 5.0, s);
        // All defaults remain — no NPE
        assertFalse(s.isFormatWallOfText());
    }

    // ── Wall of text ──────────────────────────────────────────────────────────

    @Test
    void compute_wallOfText_whenLowWhitespace() {
        // String with virtually no whitespace
        String dense = "a".repeat(1000);
        RawDocument doc = docWithText(dense);
        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertTrue(s.isFormatWallOfText());
    }

    @Test
    void compute_notWallOfText_whenEnoughWhitespace() {
        // Typical resume text with enough newlines and spaces
        String text = "John Smith\n\nBackend Engineer\n\nExperience:\n- Built APIs\n- Led team\n\n"
            + "Skills:\nJava, Docker, Kubernetes\n\nEducation:\nBSc Computer Science\n";
        RawDocument doc = docWithText(text);
        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertFalse(s.isFormatWallOfText());
    }

    // ── Page count vs YOE ─────────────────────────────────────────────────────

    @Test
    void compute_tooManyPages_under8YoeWith3Pages() {
        RawDocument doc = docWithText("some content");
        doc.setPages(List.of(page(), page(), page())); // 3 pages
        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertTrue(s.isFormatTooManyPages());
    }

    @Test
    void compute_tooManyPages_under3YoeWith2Pages() {
        RawDocument doc = docWithText("some content");
        doc.setPages(List.of(page(), page())); // 2 pages for < 3 YOE
        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 2.0, s);
        assertTrue(s.isFormatTooManyPages());
    }

    @Test
    void compute_notTooManyPages_under8YoeWith2Pages() {
        RawDocument doc = docWithText("some content");
        doc.setPages(List.of(page(), page())); // 2 pages, 5 YOE — OK
        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertFalse(s.isFormatTooManyPages());
    }

    // ── Font size ─────────────────────────────────────────────────────────────

    @Test
    void compute_fontTooSmall_whenBlockHasSmallFont() {
        RawDocument doc = docWithText("text");
        RawDocument.RawTextBlock block = new RawDocument.RawTextBlock();
        block.setText("tiny text");
        block.setFontSize(7.0f); // below 9pt threshold
        RawDocument.RawPage p = new RawDocument.RawPage();
        p.setPageNumber(1);
        p.setText("tiny text");
        p.setBlocks(List.of(block));
        doc.setPages(List.of(p));

        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertTrue(s.isFormatFontTooSmall());
    }

    @Test
    void compute_fontNotTooSmall_whenNormalSize() {
        RawDocument doc = docWithText("text");
        RawDocument.RawTextBlock block = new RawDocument.RawTextBlock();
        block.setText("normal text");
        block.setFontSize(11.0f);
        RawDocument.RawPage p = new RawDocument.RawPage();
        p.setPageNumber(1);
        p.setText("normal text");
        p.setBlocks(List.of(block));
        doc.setPages(List.of(p));

        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertFalse(s.isFormatFontTooSmall());
    }

    @Test
    void compute_mixedFonts_whenMoreThan4DistinctSizes() {
        RawDocument doc = docWithText("text");
        List<RawDocument.RawTextBlock> blocks = new ArrayList<>();
        for (float size : new float[]{8, 10, 11, 12, 14, 16}) {
            RawDocument.RawTextBlock b = new RawDocument.RawTextBlock();
            b.setText("word");
            b.setFontSize(size);
            blocks.add(b);
        }
        RawDocument.RawPage p = new RawDocument.RawPage();
        p.setPageNumber(1);
        p.setText("text");
        p.setBlocks(blocks);
        doc.setPages(List.of(p));

        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertTrue(s.isFormatMixedFonts());
    }

    // ── Inconsistent dates ────────────────────────────────────────────────────

    @Test
    void compute_inconsistentDates_whenMultipleFormats() {
        // Three different date formats in one document
        String text = "Jan 2020 – Dec 2021\n01/2022 – 03/2023\nJanuary 2024 – Present\n2019 – 2020";
        RawDocument doc = docWithText(text);
        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertTrue(s.isFormatInconsistentDates());
    }

    @Test
    void compute_consistentDates_singleFormat() {
        String text = "Jan 2020 – Dec 2021\nFeb 2022 – Mar 2023\nApr 2024 – Present";
        RawDocument doc = docWithText(text);
        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertFalse(s.isFormatInconsistentDates());
    }

    // ── Photo detection ───────────────────────────────────────────────────────

    @Test
    void compute_photoDetected_whenFirstBlockBoldAndBlank() {
        RawDocument doc = docWithText("some text");
        RawDocument.RawTextBlock photoBlock = new RawDocument.RawTextBlock();
        photoBlock.setText("");    // blank text
        photoBlock.setBold(true);  // bold flag used as photo proxy
        RawDocument.RawPage p = new RawDocument.RawPage();
        p.setPageNumber(1);
        p.setText("some text");
        p.setBlocks(List.of(photoBlock));
        doc.setPages(List.of(p));

        ResumeSignals s = new ResumeSignals();
        calculator.compute(doc, 5.0, s);
        assertTrue(s.isFormatHasPhoto());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RawDocument docWithText(String text) {
        RawDocument doc = new RawDocument();
        doc.setFullText(text);
        doc.setPages(List.of(page()));
        return doc;
    }

    private RawDocument.RawPage page() {
        RawDocument.RawPage p = new RawDocument.RawPage();
        p.setPageNumber(1);
        p.setText("");
        p.setBlocks(List.of());
        return p;
    }
}
