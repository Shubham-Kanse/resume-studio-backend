package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.YoeFit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YOE edge cases for diverse user personas.
 */
class YoePersonaTest {

    private YoeSignalCalculator calculator;

    @BeforeEach
    void setUp() { calculator = new YoeSignalCalculator(); }

    private WorkExperience role(LocalDate start, LocalDate end) {
        WorkExperience w = new WorkExperience();
        w.setStartDate(start);
        w.setEndDate(end);
        return w;
    }

    // ── Part-time work ────────────────────────────────────────────────────────

    @Test void partTime4Years_countsAs2Years() {
        WorkExperience r = role(LocalDate.now().minusYears(4), LocalDate.now().minusMonths(1));
        r.setPartTime(true);
        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(r), null, false, null, null, s, List.of());
        assertNotNull(s.getCalculatedYoe());
        assertTrue(s.getCalculatedYoe() < 2.5,
            "4 years part-time should count as ~2 years, got: " + s.getCalculatedYoe());
    }

    @Test void fullTimeAndPartTime_combinedCorrectly() {
        WorkExperience fullTime = role(LocalDate.now().minusYears(3), LocalDate.now().minusYears(1));
        WorkExperience partTime = role(LocalDate.now().minusYears(1), LocalDate.now().minusMonths(1));
        partTime.setPartTime(true);
        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(fullTime, partTime), null, false, null, null, s, List.of());
        // ~2 full + ~0.5 part = ~2.5 years
        assertTrue(s.getCalculatedYoe() < 3.0 && s.getCalculatedYoe() > 1.5,
            "Expected ~2.5 years, got: " + s.getCalculatedYoe());
    }

    // ── Sabbatical / parental leave ───────────────────────────────────────────

    @Test void sabbatical_gapNotFlagged() {
        WorkExperience job1 = role(LocalDate.now().minusYears(4), LocalDate.now().minusYears(2));
        WorkExperience sabbatical = new WorkExperience();
        sabbatical.setStartDate(LocalDate.now().minusYears(2));
        sabbatical.setEndDate(LocalDate.now().minusYears(1));
        sabbatical.setSabbatical(true);
        sabbatical.setCareerBreak(true);
        WorkExperience job2 = role(LocalDate.now().minusYears(1), LocalDate.now().minusMonths(1));

        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(job1, sabbatical, job2), null, false, null, null, s, List.of());
        assertFalse(s.isHasUnexplainedGap(), "Sabbatical gap should not be flagged as unexplained");
    }

    @Test void parentalLeave_gapNotFlagged() {
        WorkExperience job1 = role(LocalDate.now().minusYears(3), LocalDate.now().minusYears(1).minusMonths(6));
        WorkExperience leave = new WorkExperience();
        leave.setStartDate(LocalDate.now().minusYears(1).minusMonths(6));
        leave.setEndDate(LocalDate.now().minusYears(1));
        leave.setParentalLeave(true);
        leave.setCareerBreak(true);
        WorkExperience job2 = role(LocalDate.now().minusYears(1), LocalDate.now().minusMonths(1));

        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(job1, leave, job2), null, false, null, null, s, List.of());
        assertFalse(s.isHasUnexplainedGap(), "Parental leave gap should not be flagged");
    }

    // ── Contractor / consultant ───────────────────────────────────────────────

    @Test void contractor_multipleShortRoles_notFlaggedAsJobHopper() {
        // 5 contract roles of 6 months each — should not be job hopper
        List<WorkExperience> roles = new java.util.ArrayList<>();
        LocalDate start = LocalDate.now().minusYears(3);
        for (int i = 0; i < 5; i++) {
            WorkExperience r = role(start, start.plusMonths(6));
            r.setContractOrFreelance(true);
            roles.add(r);
            start = start.plusMonths(6);
        }
        ResumeSignals s = new ResumeSignals();
        calculator.compute(roles, null, false, null, null, s, List.of());
        assertFalse(s.isJobHopper(), "Contract roles should not trigger job hopper flag");
    }

    // ── Overqualified / deliberate step-down ─────────────────────────────────

    @Test void seniorApplyingToMidRole_overRange_notHardDisqualified() {
        WorkExperience r = role(LocalDate.now().minusYears(8), LocalDate.now().minusMonths(1));
        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(r), null, false, 3.0, 5.0, s, List.of());
        assertEquals(YoeFit.OVER_RANGE, s.getYoeFit(),
            "8 YOE for a 3-5 year role should be OVER_RANGE");
        // OVER_RANGE is not a hard disqualifier — classification should still consider skills
    }
}
