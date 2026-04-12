package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coherence engine edge cases for diverse user personas.
 */
class CoherencePersonaTest {

    private CoherenceEngine engine;

    @BeforeEach
    void setUp() { engine = new CoherenceEngine(); }

    private ResumeSignals base(String title, double yoe, double jdMin) {
        ResumeSignals s = new ResumeSignals();
        s.setCandidateTitle(title);
        s.setCalculatedYoe(yoe);
        s.setJdYoeMin(jdMin);
        s.setJdYoeMax(jdMin + 3);
        s.setImpactVerbRatio(0.5);
        s.setMetricDensity(0.3);
        s.setSummaryPresent(false);
        return s;
    }

    // ── Principal / manager — weak bullets are expected ───────────────────────

    @Test void principalEngineer_weakBullets_notFlagged() {
        ResumeSignals s = base("Principal Engineer", 12.0, 8.0);
        s.setImpactVerbRatio(0.1); // low — they write strategy docs
        s.setMetricDensity(0.05);
        var result = engine.check(s);
        assertFalse(result.flags().stream().anyMatch(f -> f.type().equals("TITLE_VS_BULLETS")),
            "Principal engineers should not be flagged for weak bullet metrics");
    }

    @Test void engineeringManager_weakBullets_notFlagged() {
        ResumeSignals s = base("Engineering Manager", 8.0, 5.0);
        s.setImpactVerbRatio(0.1);
        s.setMetricDensity(0.05);
        var result = engine.check(s);
        assertFalse(result.flags().stream().anyMatch(f -> f.type().equals("TITLE_VS_BULLETS")));
    }

    @Test void seniorEngineer_weakBullets_isFlagged() {
        // Senior IC should have strong bullets
        ResumeSignals s = base("Senior Software Engineer", 5.0, 4.0);
        s.setImpactVerbRatio(0.1);
        s.setMetricDensity(0.05);
        var result = engine.check(s);
        assertTrue(result.flags().stream().anyMatch(f -> f.type().equals("TITLE_VS_BULLETS")),
            "Senior engineer with weak bullets should be flagged");
    }

    // ── Manager applying for IC role — leadership claims are valid ────────────

    @Test void manager_leadershipClaims_notFlaggedAsEvidence() {
        ResumeSignals s = base("Engineering Manager", 8.0, 5.0);
        s.setSummaryPresent(true);
        s.setSummaryIsGeneric(false);
        s.setImpactVerbRatio(0.1);
        s.setMetricDensity(0.05);
        var result = engine.check(s);
        assertFalse(result.flags().stream().anyMatch(f -> f.type().equals("LEADERSHIP_VS_EVIDENCE")),
            "Actual managers should not be flagged for leadership claims");
    }

    // ── Contractor — job hopping not flagged ──────────────────────────────────

    @Test void contractor_jobHopping_notFlagged() {
        ResumeSignals s = base("Software Engineer", 5.0, 3.0);
        s.setJobHopper(false); // contractor roles excluded from job hopper detection
        var result = engine.check(s);
        assertFalse(result.flags().stream().anyMatch(f -> f.type().equals("JOB_HOPPER_VS_SENIORITY")));
    }

    // ── Fresher role — no coherence penalties ─────────────────────────────────

    @Test void fresherRole_noCoherenceFlags() {
        ResumeSignals s = new ResumeSignals();
        s.setCandidateTitle("Junior Developer");
        s.setCalculatedYoe(0.5);
        s.setJdYoeMin(0.0);
        s.setJdYoeMax(2.0);
        s.setImpactVerbRatio(0.2); // freshers have weak bullets — expected
        s.setMetricDensity(0.1);
        s.setSummaryPresent(false);
        var result = engine.check(s);
        // No flags should fire for a legitimate fresher
        assertTrue(result.flags().isEmpty() || result.penalty() < 0.1,
            "Fresher applying to fresher role should have minimal coherence penalty");
    }
}
