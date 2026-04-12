package com.resumestudio.reviewer.classification;

import com.resumestudio.reviewer.classification.ClassificationEngine.ClassificationResult;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Classification edge cases for fresher / entry-level candidates.
 */
class FresherClassificationTest {

    private ClassificationEngine engine;

    @BeforeEach
    void setUp() { engine = new ClassificationEngine(); }

    private ResumeSignals fresherSignals() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setJdYoeMin(0.0);
        s.setJdYoeMax(2.0);
        s.setCalculatedYoe(0.5);
        s.setAllMustHavesFound(true);
        s.setAllMustHavesVisible(true);
        s.setHasMissingMustHaves(false);
        s.setHasBuriedMustHaves(false);
        s.setSkillsFormat(SkillsFormat.FLAT_UNORDERED);
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        s.setYoeState(YoeState.CALCULABLE);
        s.setImpactVerbRatio(0.4);
        s.setMetricDensity(0.2);
        return s;
    }

    @Test void fresher_withAllSkills_fresherRole_isStrongOrPossibleFit() {
        ClassificationResult r = engine.classify(fresherSignals());
        assertTrue(r.verdict() == Verdict.STRONG_FIT || r.verdict() == Verdict.POSSIBLE_FIT,
            "Fresher with all skills for a fresher role should be STRONG or POSSIBLE fit, got: " + r.verdict());
    }

    @Test void fresher_cannotDetermineYoe_fresherRole_notWeakFit() {
        ResumeSignals s = fresherSignals();
        s.setYoeFit(YoeFit.CANNOT_DETERMINE);
        s.setCalculatedYoe(null);
        ClassificationResult r = engine.classify(s);
        assertNotEquals(Verdict.WEAK_FIT, r.verdict(),
            "CANNOT_DETERMINE YOE for a fresher role should not produce WEAK_FIT");
        assertNotEquals(Verdict.NO_FIT, r.verdict());
    }

    @Test void fresher_seniorityCalibration_isMatched_forFresherRole() {
        ClassificationResult r = engine.classify(fresherSignals());
        assertEquals(SeniorityCalibration.MATCHED, r.seniorityCalibration(),
            "Fresher applying to fresher role should be MATCHED seniority");
    }

    @Test void fresher_tailoringScore_notCappedTooLow() {
        ClassificationResult r = engine.classify(fresherSignals());
        // For POSSIBLE_FIT or STRONG_FIT, tailoring score should be reasonable
        assertTrue(r.tailoringScore() >= 4,
            "Fresher with good skills should have tailoring score >= 4, got: " + r.tailoringScore());
    }

    @Test void seniorApplyingToFresherRole_isOverQualified() {
        ResumeSignals s = fresherSignals();
        s.setYoeFit(YoeFit.OVER_RANGE);
        s.setCalculatedYoe(7.0);
        ClassificationResult r = engine.classify(s);
        assertEquals(SeniorityCalibration.OVER_QUALIFIED, r.seniorityCalibration());
    }

    @Test void fresherWithMissingSkills_fresherRole_isWeakFit() {
        ResumeSignals s = fresherSignals();
        s.setAllMustHavesFound(false);
        s.setAllMustHavesVisible(false);
        s.setHasMissingMustHaves(true);
        ClassificationResult r = engine.classify(s);
        // Missing skills should still produce weak/no fit even for freshers
        assertTrue(r.verdict() == Verdict.WEAK_FIT || r.verdict() == Verdict.NO_FIT
            || r.verdict() == Verdict.POSSIBLE_FIT,
            "Fresher missing required skills should not be STRONG_FIT");
    }
}
