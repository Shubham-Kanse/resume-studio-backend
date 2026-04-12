package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoherenceEngineTest {

    private CoherenceEngine engine;

    @BeforeEach
    void setUp() { engine = new CoherenceEngine(); }

    private ResumeSignals base() {
        ResumeSignals s = new ResumeSignals();
        s.setCalculatedYoe(5.0);
        s.setCandidateTitle("Software Engineer");
        s.setImpactVerbRatio(0.6);
        s.setMetricDensity(0.4);
        s.setJdYoeMin(3.0); // non-fresher role — coherence checks should apply
        s.setJdYoeMax(6.0);
        return s;
    }

    @Test void noFlags_cleanResume() {
        assertTrue(engine.check(base()).flags().isEmpty());
    }

    @Test void seniorityVsYoe_flagged_whenSeniorTitleButLowYoe() {
        ResumeSignals s = base();
        s.setCalculatedYoe(1.5);
        s.setCandidateTitle("Senior Software Engineer");
        var result = engine.check(s);
        assertTrue(result.flags().stream().anyMatch(f -> f.type().equals("SENIORITY_VS_YOE")));
    }

    @Test void seniorityVsYoe_notFlagged_whenYoeIsAdequate() {
        ResumeSignals s = base();
        s.setCalculatedYoe(4.0);
        s.setCandidateTitle("Senior Software Engineer");
        assertTrue(engine.check(s).flags().stream().noneMatch(f -> f.type().equals("SENIORITY_VS_YOE")));
    }

    @Test void titleVsBullets_flagged_whenSeniorTitleButWeakBullets() {
        ResumeSignals s = base();
        s.setCandidateTitle("Senior Engineer");
        s.setImpactVerbRatio(0.1);
        s.setMetricDensity(0.05);
        assertTrue(engine.check(s).flags().stream().anyMatch(f -> f.type().equals("TITLE_VS_BULLETS")));
    }

    @Test void positioningVsExperience_flagged_whenTitleMissAndMostSkillsMissing() {
        ResumeSignals s = base();
        s.setTitleMatch(TitleMatch.MISS);
        s.setHasMissingMustHaves(true);
        // 8 skills, 7 missing = 87.5% missing
        List<SkillMatchResult> results = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            SkillMatchResult r = new SkillMatchResult("skill" + i, true);
            r.setVisibility(SkillVisibility.MISSING);
            results.add(r);
        }
        SkillMatchResult found = new SkillMatchResult("skill7", true);
        found.setVisibility(SkillVisibility.SURFACE);
        results.add(found);
        s.setMustHaveResults(results);
        assertTrue(engine.check(s).flags().stream().anyMatch(f -> f.type().equals("POSITIONING_VS_EXPERIENCE")));
    }

    @Test void penalty_accumulatesAcrossMultipleFlags() {
        ResumeSignals s = base();
        s.setCalculatedYoe(1.5);
        s.setCandidateTitle("Senior Lead Engineer");
        s.setImpactVerbRatio(0.1);
        s.setMetricDensity(0.05);
        double penalty = engine.check(s).penalty();
        assertTrue(penalty > 0.08, "Expected penalty > 0.08, got " + penalty);
    }

    @Test void transferableScore_computedFromMatchedSkills() {
        ResumeSignals s = base();
        List<SkillMatchResult> results = new java.util.ArrayList<>();
        SkillMatchResult found = new SkillMatchResult("Java", true);
        found.setVisibility(SkillVisibility.SURFACE);
        results.add(found);
        SkillMatchResult missing = new SkillMatchResult("Kubernetes", true);
        missing.setVisibility(SkillVisibility.MISSING);
        results.add(missing);
        s.setMustHaveResults(results);
        var result = engine.check(s);
        assertEquals(0.5, result.transferableSkillScore(), 0.01);
    }
}
