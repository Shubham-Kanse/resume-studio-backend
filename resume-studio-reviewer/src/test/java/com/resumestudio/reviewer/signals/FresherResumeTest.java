package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.YoeFit;
import com.resumestudio.reviewer.model.enums.YoeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fresher / entry-level resume edge cases.
 * Ensures the pipeline does not penalise candidates applying to roles they're qualified for.
 */
class FresherResumeTest {

    private YoeSignalCalculator calculator;

    @BeforeEach
    void setUp() { calculator = new YoeSignalCalculator(); }

    // ── YOE fit for fresher roles ─────────────────────────────────────────────

    @Test void fresher_zeroYoe_fresherRole_isInRange() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(), 0.0, true, 0.0, 2.0, s, List.of());
        assertEquals(YoeFit.IN_RANGE, s.getYoeFit(),
            "0 YOE for a 0-2 year role should be IN_RANGE");
    }

    @Test void fresher_noExperience_fresherRole_cannotDetermineNotPenalised() {
        // No experience, no explicit YOE, JD requires 0-1 years
        ResumeSignals s = new ResumeSignals();
        calculator.compute(null, null, false, 0.0, 1.0, s, List.of());
        // CANNOT_DETERMINE is acceptable — should not be UNDER_RANGE_SIGNIFICANT
        assertNotEquals(YoeFit.UNDER_RANGE_SIGNIFICANT, s.getYoeFit());
        assertNotEquals(YoeFit.UNDER_RANGE_MINOR, s.getYoeFit());
    }

    @Test void fresher_noExperience_noJdRequirement_isInRange() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute(null, null, false, null, null, s, List.of());
        // No JD requirement — should not penalise
        assertNotEquals(YoeFit.UNDER_RANGE_SIGNIFICANT, s.getYoeFit());
    }

    @Test void fresher_internship6months_fresherRole_isInRange() {
        WorkExperience internship = new WorkExperience();
        internship.setStartDate(LocalDate.now().minusMonths(6));
        internship.setCurrent(true);
        internship.setTitle("Software Engineering Intern");

        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(internship), null, false, 0.0, 2.0, s, List.of());
        assertEquals(YoeFit.IN_RANGE, s.getYoeFit(),
            "6-month internship for a 0-2 year role should be IN_RANGE");
    }

    @Test void seniorRole_fresherApplying_isUnderRangeSignificant() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(), 0.0, true, 5.0, 8.0, s, List.of());
        assertEquals(YoeFit.UNDER_RANGE_SIGNIFICANT, s.getYoeFit(),
            "0 YOE for a 5-8 year role should be UNDER_RANGE_SIGNIFICANT");
    }

    @Test void overqualified_seniorApplyingToFresherRole_isOverRange() {
        WorkExperience role = new WorkExperience();
        role.setStartDate(LocalDate.now().minusYears(6));
        role.setCurrent(true);

        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(role), null, false, 0.0, 2.0, s, List.of());
        assertEquals(YoeFit.OVER_RANGE, s.getYoeFit(),
            "6 YOE for a 0-2 year role should be OVER_RANGE");
    }

    @Test void fresher_recentGrad_gapCoveredByEducation_notFlagged() {
        // Graduated 3 months ago, no job yet — gap should be covered by education
        var edu = new com.resumestudio.reviewer.model.Education();
        edu.setGraduationYear(LocalDate.now().minusMonths(3).getYear());
        edu.setStartYear(edu.getGraduationYear() - 4);

        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(), null, false, 0.0, 2.0, s, List.of(edu));
        assertFalse(s.isHasUnexplainedGap(), "Post-graduation gap should not be flagged");
    }
}
