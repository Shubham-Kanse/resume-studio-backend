package com.resumestudio.reviewer.classification;

import com.resumestudio.reviewer.classification.ClassificationEngine.ClassificationResult;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.enums.*;
import com.resumestudio.reviewer.signals.CoherenceEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases for classification not covered in ClassificationEngineTest.
 */
class ClassificationEdgeCaseTest {

    private ClassificationEngine engine;

    @BeforeEach
    void setUp() { engine = new ClassificationEngine(); }

    private ResumeSignals goodSignals() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setAllMustHavesFound(true);
        s.setAllMustHavesVisible(true);
        s.setHasMissingMustHaves(false);
        s.setHasBuriedMustHaves(false);
        s.setSkillsFormat(SkillsFormat.FLAT_ORDERED);
        s.setCurrentCompanyTier(CompanyTier.TIER_1);
        s.setYoeState(YoeState.EXPLICIT);
        s.setImpactVerbRatio(0.7);
        s.setMetricDensity(0.4);
        return s;
    }

    @Test void noFit_whenFinalScoreVeryLow() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.MISS);
        s.setYoeFit(YoeFit.CANNOT_DETERMINE);
        s.setAllMustHavesFound(false);
        s.setAllMustHavesVisible(false);
        s.setHasMissingMustHaves(true);
        s.setSkillsFormat(SkillsFormat.NO_SECTION);
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        s.setImpactVerbRatio(0.0);
        s.setMetricDensity(0.0);
        Verdict v = engine.classify(s).verdict();
        assertTrue(v == Verdict.NO_FIT || v == Verdict.WEAK_FIT,
            "Expected NO_FIT or WEAK_FIT for all-bad signals, got: " + v);
    }

    @Test void interviewLikelihood_veryLikely_forStrongFitHighConfidence() {
        ClassificationResult r = engine.classify(goodSignals());
        assertEquals(Verdict.STRONG_FIT, r.verdict());
        assertEquals(InterviewLikelihood.VERY_LIKELY, r.interviewLikelihood());
    }

    @Test void interviewLikelihood_veryUnlikely_forNoFit() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.MISS);
        s.setYoeFit(YoeFit.CANNOT_DETERMINE);
        s.setAllMustHavesFound(false);
        s.setAllMustHavesVisible(false);
        s.setHasMissingMustHaves(true);
        s.setSkillsFormat(SkillsFormat.NO_SECTION);
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        ClassificationResult r = engine.classify(s);
        assertTrue(r.interviewLikelihood() == InterviewLikelihood.VERY_UNLIKELY
            || r.interviewLikelihood() == InterviewLikelihood.UNLIKELY);
    }

    @Test void scanDuration_strongFit_is60() {
        assertEquals(60, engine.classify(goodSignals()).scanDuration());
    }

    @Test void scanDuration_noFit_is8() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.MISS);
        s.setYoeFit(YoeFit.CANNOT_DETERMINE);
        s.setAllMustHavesFound(false);
        s.setAllMustHavesVisible(false);
        s.setHasMissingMustHaves(true);
        s.setSkillsFormat(SkillsFormat.NO_SECTION);
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        int dur = engine.classify(s).scanDuration();
        assertTrue(dur <= 15, "Expected short scan for bad signals, got: " + dur);
    }

    @Test void coherencePenalty_downgrades_strongToWeak() {
        ResumeSignals s = goodSignals();
        // Build a coherence result with high penalty
        var flags = new java.util.ArrayList<CoherenceEngine.CoherenceFlag>();
        flags.add(new CoherenceEngine.CoherenceFlag("TEST", "test", com.resumestudio.reviewer.model.enums.ImpactLevel.HIGH));
        flags.add(new CoherenceEngine.CoherenceFlag("TEST2", "test2", com.resumestudio.reviewer.model.enums.ImpactLevel.HIGH));
        flags.add(new CoherenceEngine.CoherenceFlag("TEST3", "test3", com.resumestudio.reviewer.model.enums.ImpactLevel.HIGH));
        var coherence = new CoherenceEngine.CoherenceResult(flags, 0.45, 0.5, "ADJACENT");
        Verdict v = engine.classify(s, coherence).verdict();
        // 0.45 penalty should pull STRONG_FIT down
        assertNotEquals(Verdict.STRONG_FIT, v);
    }

    @Test void nullSignals_returnsWeakFitNotCrash() {
        ClassificationResult r = engine.classify(null);
        assertEquals(Verdict.WEAK_FIT, r.verdict());
        assertEquals(Confidence.LOW, r.confidence());
    }
}
