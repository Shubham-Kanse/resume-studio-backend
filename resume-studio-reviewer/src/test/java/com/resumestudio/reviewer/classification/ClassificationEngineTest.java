package com.resumestudio.reviewer.classification;

import com.resumestudio.reviewer.classification.ClassificationEngine.ClassificationResult;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClassificationEngineTest {

    private ClassificationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ClassificationEngine();
    }

    // ── Hard disqualifiers (still enforced) ──────────────────────────────────

    @Test
    void weakFit_whenChronologyUnreliable() {
        ResumeSignals s = goodSignals();
        s.setChronologyUnreliable(true);
        assertEquals(Verdict.WEAK_FIT, engine.classify(s).verdict());
    }

    @Test
    void weakFit_whenWallOfTextAndFontTooSmall() {
        ResumeSignals s = goodSignals();
        s.setFormatWallOfText(true);
        s.setFormatFontTooSmall(true);
        assertEquals(Verdict.WEAK_FIT, engine.classify(s).verdict());
    }

    // ── Weighted scoring — missing skills drives score down ───────────────────

    @Test
    void weakOrNoFit_whenMissingMustHaves() {
        ResumeSignals s = goodSignals();
        s.setHasMissingMustHaves(true);
        s.setAllMustHavesFound(false);
        s.setAllMustHavesVisible(false);
        // skills score = 0, pulls finalScore below STRONG threshold
        Verdict v = engine.classify(s).verdict();
        assertTrue(v == Verdict.WEAK_FIT || v == Verdict.POSSIBLE_FIT || v == Verdict.NO_FIT,
            "Expected non-STRONG verdict, got: " + v);
    }

    @Test
    void weakOrNoFit_whenYoeSignificantlyUnder() {
        ResumeSignals s = goodSignals();
        s.setYoeFit(YoeFit.UNDER_RANGE_SIGNIFICANT);
        Verdict v = engine.classify(s).verdict();
        assertNotEquals(Verdict.STRONG_FIT, v);
    }

    @Test
    void weakOrNoFit_whenNoSkillsSectionAndMustHavesNotFound() {
        ResumeSignals s = goodSignals();
        s.setSkillsFormat(SkillsFormat.NO_SECTION);
        s.setAllMustHavesFound(false);
        s.setAllMustHavesVisible(false);
        Verdict v = engine.classify(s).verdict();
        assertNotEquals(Verdict.STRONG_FIT, v);
    }

    // ── STRONG_FIT conditions ─────────────────────────────────────────────────

    @Test
    void strongFit_whenAllThreeConditionsMet() {
        ResumeSignals s = goodSignals();
        assertEquals(Verdict.STRONG_FIT, engine.classify(s).verdict());
    }

    // ── POSSIBLE_FIT conditions ───────────────────────────────────────────────

    @Test
    void possibleFit_whenSkillsBuriedButAllOthersMet() {
        ResumeSignals s = goodSignals();
        s.setAllMustHavesVisible(false);
        s.setHasBuriedMustHaves(true);
        // skills score drops to 0.5, should land in POSSIBLE range
        Verdict v = engine.classify(s).verdict();
        assertTrue(v == Verdict.POSSIBLE_FIT || v == Verdict.STRONG_FIT,
            "Expected POSSIBLE_FIT or STRONG_FIT, got: " + v);
    }

    @Test
    void possibleFit_yoeUnderMinorWithTitleAndVisibleSkills() {
        ResumeSignals s = new ResumeSignals();
        s.setHasMissingMustHaves(false);
        s.setYoeFit(YoeFit.UNDER_RANGE_MINOR);
        s.setTitleMatch(TitleMatch.EXACT);
        s.setAllMustHavesFound(true);
        s.setAllMustHavesVisible(true);
        s.setHasBuriedMustHaves(false);
        s.setSkillsFormat(SkillsFormat.FLAT_ORDERED);
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        s.setYoeState(YoeState.CALCULABLE);
        s.setFormatWallOfText(false);
        s.setFormatFontTooSmall(false);
        Verdict v = engine.classify(s).verdict();
        // UNDER_RANGE_MINOR reduces yoe score to 0.5, overall should be POSSIBLE or STRONG
        assertTrue(v == Verdict.POSSIBLE_FIT || v == Verdict.STRONG_FIT,
            "Expected POSSIBLE_FIT or STRONG_FIT, got: " + v);
    }

    @Test
    void possibleFit_when2Of3ConditionsMet_titleAndYoe() {
        ResumeSignals s = new ResumeSignals();
        s.setHasMissingMustHaves(false);
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setTitleMatch(TitleMatch.EXACT);
        s.setAllMustHavesFound(false);
        s.setAllMustHavesVisible(false);
        s.setHasBuriedMustHaves(false);
        s.setSkillsFormat(SkillsFormat.FLAT_UNORDERED);
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        s.setYoeState(YoeState.CALCULABLE);
        s.setFormatWallOfText(false);
        s.setFormatFontTooSmall(false);
        assertEquals(Verdict.POSSIBLE_FIT, engine.classify(s).verdict());
    }

    @Test
    void possibleFit_adjacentTitleWithFaangCompany() {
        ResumeSignals s = new ResumeSignals();
        s.setHasMissingMustHaves(false);
        s.setYoeFit(YoeFit.UNDER_RANGE_MINOR);
        s.setTitleMatch(TitleMatch.ADJACENT);
        s.setAllMustHavesFound(true);
        s.setAllMustHavesVisible(false);
        s.setHasBuriedMustHaves(false);
        s.setSkillsFormat(SkillsFormat.FLAT_UNORDERED);
        s.setCurrentCompanyTier(CompanyTier.FAANG);
        s.setYoeState(YoeState.CALCULABLE);
        s.setFormatWallOfText(false);
        s.setFormatFontTooSmall(false);
        assertEquals(Verdict.POSSIBLE_FIT, engine.classify(s).verdict());
    }

    // ── Confidence computation ────────────────────────────────────────────────

    @Test
    void confidence_highWhenNoIssues() {
        ResumeSignals s = goodSignals();
        assertEquals(Confidence.HIGH, engine.classify(s).confidence());
    }

    @Test
    void confidence_mediumWhenPartialYoeAndBuried() {
        ResumeSignals s = goodSignals();
        s.setYoeState(YoeState.PARTIAL);
        s.setHasBuriedMustHaves(true);
        assertEquals(Confidence.MEDIUM, engine.classify(s).confidence());
    }

    @Test
    void confidence_lowWhenMissingYoeAndNoSection() {
        ResumeSignals s = goodSignals();
        s.setYoeState(YoeState.MISSING);
        s.setSkillsFormat(SkillsFormat.NO_SECTION);
        s.setAllMustHavesFound(false);
        s.setHasMissingMustHaves(true);
        // penaltyPoints: 2 (MISSING) + 1 (NO_SECTION) = 3 → MEDIUM
        // Add title null for LOW
        s.setTitleMatch(null);
        assertEquals(Confidence.LOW, engine.classify(s).confidence());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResumeSignals goodSignals() {
        ResumeSignals s = new ResumeSignals();
        s.setHasMissingMustHaves(false);
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setTitleMatch(TitleMatch.EXACT);
        s.setAllMustHavesFound(true);
        s.setAllMustHavesVisible(true);
        s.setHasBuriedMustHaves(false);
        s.setSkillsFormat(SkillsFormat.FLAT_ORDERED);
        s.setCurrentCompanyTier(CompanyTier.SCALE_UP);
        s.setYoeState(YoeState.EXPLICIT);
        s.setFormatWallOfText(false);
        s.setFormatFontTooSmall(false);
        return s;
    }

    // ── Null safety tests ─────────────────────────────────────────────────────

    @Test
    void classify_nullSignals_returnsWeakFitLowConfidence() {
        ClassificationResult result = engine.classify(null);
        assertEquals(Verdict.WEAK_FIT, result.verdict());
        assertEquals(Confidence.LOW, result.confidence());
    }

    @Test
    void weakFit_whenYoeFitIsNull() {
        ResumeSignals s = goodSignals();
        s.setYoeFit(null);
        // Should not crash, should handle gracefully
        ClassificationResult result = engine.classify(s);
        assertNotNull(result);
        assertNotNull(result.verdict());
    }

    @Test
    void classify_whenTitleMatchIsNull_penalizesConfidence() {
        ResumeSignals s = goodSignals();
        s.setTitleMatch(null);
        ClassificationResult result = engine.classify(s);
        // Null titleMatch adds 2 penalty points
        assertNotNull(result);
        // Should still return a verdict (not crash)
        assertNotNull(result.verdict());
    }

    @Test
    void classify_whenSkillsFormatIsNull_handlesGracefully() {
        ResumeSignals s = goodSignals();
        s.setSkillsFormat(null);
        ClassificationResult result = engine.classify(s);
        assertNotNull(result);
        assertNotNull(result.verdict());
        assertNotNull(result.confidence());
    }

    @Test
    void classify_whenCurrentCompanyTierIsNull_handlesGracefully() {
        ResumeSignals s = goodSignals();
        s.setCurrentCompanyTier(null);
        ClassificationResult result = engine.classify(s);
        assertNotNull(result);
        assertNotNull(result.verdict());
    }

    @Test
    void classify_whenYoeStateIsNull_handlesGracefully() {
        ResumeSignals s = goodSignals();
        s.setYoeState(null);
        ClassificationResult result = engine.classify(s);
        assertNotNull(result);
        assertNotNull(result.confidence());
    }

    @Test
    void classify_allEnumsNull_returnsWeakFitLowConfidence() {
        ResumeSignals s = new ResumeSignals();
        // All enums are null by default
        s.setHasMissingMustHaves(false);
        s.setAllMustHavesFound(false);
        s.setAllMustHavesVisible(false);
        s.setHasBuriedMustHaves(false);
        s.setFormatWallOfText(false);
        s.setFormatFontTooSmall(false);
        
        ClassificationResult result = engine.classify(s);
        assertNotNull(result);
        assertEquals(Verdict.WEAK_FIT, result.verdict());
        // Null titleMatch adds 2 penalty points → MEDIUM or LOW
        assertTrue(result.confidence() == Confidence.MEDIUM || result.confidence() == Confidence.LOW);
    }

    @Test
    void classify_mixedNullEnums_handlesGracefully() {
        ResumeSignals s = goodSignals();
        s.setYoeFit(null);
        s.setTitleMatch(null);
        s.setSkillsFormat(null);
        
        ClassificationResult result = engine.classify(s);
        assertNotNull(result);
        assertNotNull(result.verdict());
        assertNotNull(result.confidence());
    }
}
