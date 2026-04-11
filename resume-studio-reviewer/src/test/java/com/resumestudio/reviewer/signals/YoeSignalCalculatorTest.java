package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.Education;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.YoeFit;
import com.resumestudio.reviewer.model.enums.YoeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YoeSignalCalculatorTest {

    private YoeSignalCalculator calculator;

    @BeforeEach
    void setUp() { calculator = new YoeSignalCalculator(); }

    private WorkExperience role(LocalDate start, LocalDate end) {
        WorkExperience w = new WorkExperience();
        w.setStartDate(start);
        w.setEndDate(end);
        return w;
    }

    private WorkExperience currentRole(LocalDate start) {
        WorkExperience w = new WorkExperience();
        w.setStartDate(start);
        w.setCurrent(true);
        return w;
    }

    // ── YOE fit ───────────────────────────────────────────────────────────────

    @Test void inRange_whenYoeMatchesJdMin() {
        ResumeSignals s = new ResumeSignals();
        var r = role(LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1));
        calculator.compute(List.of(r), null, false, 4.0, 7.0, s);
        assertEquals(YoeFit.IN_RANGE, s.getYoeFit());
    }

    @Test void underRangeMinor_whenWithin1_5Years() {
        ResumeSignals s = new ResumeSignals();
        var r = role(LocalDate.of(2022, 1, 1), LocalDate.of(2025, 1, 1)); // ~3 yrs
        calculator.compute(List.of(r), null, false, 4.0, 7.0, s);
        assertEquals(YoeFit.UNDER_RANGE_MINOR, s.getYoeFit());
    }

    @Test void underRangeSignificant_whenMoreThan2YearsUnder() {
        ResumeSignals s = new ResumeSignals();
        var r = role(LocalDate.of(2023, 1, 1), LocalDate.of(2025, 1, 1)); // ~2 yrs
        calculator.compute(List.of(r), null, false, 6.0, 10.0, s);
        assertEquals(YoeFit.UNDER_RANGE_SIGNIFICANT, s.getYoeFit());
    }

    @Test void overRange_whenMoreThan4YearsOver() {
        ResumeSignals s = new ResumeSignals();
        var r = role(LocalDate.of(2010, 1, 1), LocalDate.of(2025, 1, 1)); // ~15 yrs
        calculator.compute(List.of(r), null, false, 3.0, 5.0, s);
        assertEquals(YoeFit.OVER_RANGE, s.getYoeFit());
    }

    @Test void inRange_whenNoJdMinSpecified() {
        ResumeSignals s = new ResumeSignals();
        var r = role(LocalDate.of(2022, 1, 1), LocalDate.of(2025, 1, 1));
        calculator.compute(List.of(r), null, false, null, null, s);
        assertEquals(YoeFit.IN_RANGE, s.getYoeFit());
    }

    // ── YOE state ─────────────────────────────────────────────────────────────

    @Test void state_explicit_whenExplicitInSummary() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(), 5.0, true, 4.0, 7.0, s);
        assertEquals(YoeState.EXPLICIT, s.getYoeState());
        assertEquals(5.0, s.getCalculatedYoe(), 0.01);
    }

    @Test void state_missing_whenNoExperienceAndNoExplicit() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute(List.of(), null, false, 4.0, 7.0, s);
        assertEquals(YoeState.MISSING, s.getYoeState());
        assertEquals(YoeFit.CANNOT_DETERMINE, s.getYoeFit());
    }

    @Test void state_calculable_whenAllDatesPresent() {
        ResumeSignals s = new ResumeSignals();
        var r = role(LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1));
        calculator.compute(List.of(r), null, false, null, null, s);
        assertEquals(YoeState.CALCULABLE, s.getYoeState());
    }

    @Test void state_partial_whenSomeDatesMissing() {
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1));
        var r2 = new WorkExperience(); // no dates
        calculator.compute(List.of(r1, r2), null, false, null, null, s);
        assertEquals(YoeState.PARTIAL, s.getYoeState());
    }

    // ── Gap detection ─────────────────────────────────────────────────────────

    @Test void gap_detected_whenMoreThan6Months() {
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2018, 1, 1), LocalDate.of(2019, 1, 1));
        var r2 = role(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1)); // 12-month gap
        calculator.compute(List.of(r1, r2), null, false, null, null, s);
        assertTrue(s.isHasUnexplainedGap());
    }

    @Test void gap_notDetected_whenLessThan6Months() {
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1));
        var r2 = role(LocalDate.of(2022, 4, 1), LocalDate.of(2025, 1, 1)); // 3-month gap
        calculator.compute(List.of(r1, r2), null, false, null, null, s);
        assertFalse(s.isHasUnexplainedGap());
    }

    @Test void gap_coveredByEducation_notFlagged() {
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2016, 1, 1), LocalDate.of(2017, 6, 1));
        var r2 = role(LocalDate.of(2021, 9, 1), LocalDate.of(2025, 1, 1));

        Education edu = new Education();
        edu.setStartYear(2017);
        edu.setGraduationYear(2021);

        calculator.compute(List.of(r1, r2), null, false, null, null, s, List.of(edu));
        assertFalse(s.isHasUnexplainedGap());
    }

    @Test void gap_coveredByEducation_noStartYear_uses4YearAssumption() {
        // Education with only graduationYear — engine assumes 4-year degree
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2016, 1, 1), LocalDate.of(2017, 6, 1));
        var r2 = role(LocalDate.of(2021, 9, 1), LocalDate.of(2025, 1, 1));

        Education edu = new Education();
        edu.setGraduationYear(2021); // startYear null → assumed 2017

        calculator.compute(List.of(r1, r2), null, false, null, null, s, List.of(edu));
        assertFalse(s.isHasUnexplainedGap());
    }

    @Test void gap_notCoveredByEducation_whenGraduationYearNull() {
        // Education with no graduationYear must be skipped — gap remains flagged
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2016, 1, 1), LocalDate.of(2017, 6, 1));
        var r2 = role(LocalDate.of(2021, 9, 1), LocalDate.of(2025, 1, 1)); // 4-year gap

        Education edu = new Education();
        edu.setStartYear(2017); // graduationYear null → skipped

        calculator.compute(List.of(r1, r2), null, false, null, null, s, List.of(edu));
        assertTrue(s.isHasUnexplainedGap());
    }

    // ── Job hopping ───────────────────────────────────────────────────────────

    @Test void jobHopper_detected_when3ShortRoles() {
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 9, 1));
        var r2 = role(LocalDate.of(2020, 10, 1), LocalDate.of(2021, 6, 1));
        var r3 = role(LocalDate.of(2021, 7, 1), LocalDate.of(2022, 3, 1));
        calculator.compute(List.of(r1, r2, r3), null, false, null, null, s);
        assertTrue(s.isJobHopper());
    }

    @Test void jobHopper_notFlagged_forContractRoles() {
        ResumeSignals s = new ResumeSignals();
        WorkExperience r1 = role(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 9, 1));
        r1.setContractOrFreelance(true);
        WorkExperience r2 = role(LocalDate.of(2020, 10, 1), LocalDate.of(2021, 6, 1));
        r2.setContractOrFreelance(true);
        WorkExperience r3 = role(LocalDate.of(2021, 7, 1), LocalDate.of(2022, 3, 1));
        r3.setContractOrFreelance(true);
        calculator.compute(List.of(r1, r2, r3), null, false, null, null, s);
        assertFalse(s.isJobHopper());
    }

    // ── Overlapping roles ─────────────────────────────────────────────────────

    @Test void overlap_detected_whenTwoRolesOverlap() {
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 6, 1));
        var r2 = role(LocalDate.of(2021, 1, 1), LocalDate.of(2023, 1, 1)); // overlaps r1
        calculator.compute(List.of(r1, r2), null, false, null, null, s);
        assertTrue(s.isHasConcurrentRoles());
    }

    // ── Overlapping YOE not double-counted ────────────────────────────────────

    @Test void yoe_overlappingRoles_notDoubleCounted() {
        ResumeSignals s = new ResumeSignals();
        var r1 = role(LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1)); // 2 yrs
        var r2 = role(LocalDate.of(2021, 1, 1), LocalDate.of(2023, 1, 1)); // overlaps — total should be 3 not 4
        calculator.compute(List.of(r1, r2), null, false, null, null, s);
        assertEquals(3.0, s.getCalculatedYoe(), 0.1);
    }
}
