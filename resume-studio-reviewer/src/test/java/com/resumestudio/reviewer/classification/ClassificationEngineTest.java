package com.resumestudio.reviewer.classification;

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

    // ── WEAK_FIT hard-fail conditions ─────────────────────────────────────────

    @Test
    void weakFit_whenMissingMustHaves() {
        ResumeSignals s = goodSignals();
        s.setHasMissingMustHaves(true);
        assertEquals(Verdict.WEAK_FIT, engine.classify(s).verdict());
    }

    @Test
    void weakFit_whenYoeSignificantlyUnder() {
        ResumeSignals s = goodSignals();
        s.setYoeFit(YoeFit.UNDER_RANGE_SIGNIFICANT);
        assertEquals(Verdict.WEAK_FIT, engine.classify(s).verdict());
    }

    @Test
    void weakFit_whenTitleMissAndMustHavesNotVisible() {
        ResumeSignals s = goodSignals();
        s.setTitleMatch(TitleMatch.MISS);
        s.setAllMustHavesVisible(false);
        assertEquals(Verdict.WEAK_FIT, engine.classify(s).verdict());
    }

    @Test
    void weakFit_whenNoSkillsSectionAndMustHavesNotFound() {
        ResumeSignals s = goodSignals();
        s.setSkillsFormat(SkillsFormat.NO_SECTION);
        s.setAllMustHavesFound(false);
        assertEquals(Verdict.WEAK_FIT, engine.classify(s).verdict());
    }

    @Test
    void weakFit_whenWallOfTextAndFontTooSmall() {
        ResumeSignals s = goodSignals();
        s.setFormatWallOfText(true);
        s.setFormatFontTooSmall(true);
        assertEquals(Verdict.WEAK_FIT, engine.classify(s).verdict());
    }

    // ── STRONG_FIT conditions ─────────────────────────────────────────────────

    @Test
    void strongFit_whenAllThreeConditionsMet() {
        ResumeSignals s = goodSignals(); // all three conditions satisfied
        assertEquals(Verdict.STRONG_FIT, engine.classify(s).verdict());
    }

    @Test
    void strongFit_withAdjacentTitleAndOverRange() {
        ResumeSignals s = goodSignals();
        s.setTitleMatch(TitleMatch.ADJACENT);
        s.setYoeFit(YoeFit.OVER_RANGE);
        assertEquals(Verdict.STRONG_FIT, engine.classify(s).verdict());
    }

    // ── POSSIBLE_FIT conditions ───────────────────────────────────────────────

    @Test
    void possibleFit_whenSkillsBuriedButAllOthersMet() {
        ResumeSignals s = goodSignals();
        s.setAllMustHavesVisible(false);
        s.setHasBuriedMustHaves(true);
        assertEquals(Verdict.POSSIBLE_FIT, engine.classify(s).verdict());
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
}
