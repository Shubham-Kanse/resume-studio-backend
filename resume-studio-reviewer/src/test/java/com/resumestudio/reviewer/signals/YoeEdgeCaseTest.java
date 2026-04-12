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
 * Edge cases not covered in YoeSignalCalculatorTest.
 */
class YoeEdgeCaseTest {

    private YoeSignalCalculator calculator;

    @BeforeEach
    void setUp() { calculator = new YoeSignalCalculator(); }

    private WorkExperience role(LocalDate start, LocalDate end) {
        WorkExperience w = new WorkExperience();
        w.setStartDate(start);
        w.setEndDate(end);
        return w;
    }

    private WorkExperience current(LocalDate start) {
        WorkExperience w = new WorkExperience();
        w.setStartDate(start);
        w.setCurrent(true);
        return w;
    }

    @Test void overlappingRoles_yoeNotDoubleCountedBeyondActualTime() {
        // Two roles running simultaneously for 2 years — should count as ~2 years, not 4
        LocalDate start = LocalDate.now().minusYears(2);
        LocalDate end = LocalDate.now().minusMonths(1);
        WorkExperience r1 = role(start, end);
        WorkExperience r2 = role(start, end); // exact overlap

        ResumeSignals signals = new ResumeSignals();
        calculator.compute(List.of(r1, r2), null, false, null, null, signals, List.of());

        // YOE should be ~2, not ~4
        assertNotNull(signals.getCalculatedYoe());
        assertTrue(signals.getCalculatedYoe() <= 2.5,
            "Overlapping roles should not double-count YOE, got: " + signals.getCalculatedYoe());
    }

    @Test void currentRoleWithNoEndDate_yoeStillComputed() {
        WorkExperience r = current(LocalDate.now().minusYears(3));
        ResumeSignals signals = new ResumeSignals();
        calculator.compute(List.of(r), null, false, null, null, signals, List.of());
        assertNotNull(signals.getCalculatedYoe());
        assertTrue(signals.getCalculatedYoe() >= 2.5);
    }

    @Test void recentGraduate_zeroYoe_doesNotCrash() {
        ResumeSignals signals = new ResumeSignals();
        calculator.compute(List.of(), 0.0, false, 3.0, 5.0, signals, List.of());
        // With 0 YOE and JD requiring 3-5, should be UNDER_RANGE_SIGNIFICANT or CANNOT_DETERMINE
        assertNotNull(signals.getYoeFit());
        assertTrue(signals.getYoeFit() == YoeFit.UNDER_RANGE_SIGNIFICANT
            || signals.getYoeFit() == YoeFit.CANNOT_DETERMINE);
    }

    @Test void nullExperience_doesNotCrash() {
        ResumeSignals signals = new ResumeSignals();
        assertDoesNotThrow(() ->
            calculator.compute(null, null, false, null, null, signals, List.of()));
    }

    @Test void partialDatesYearOnly_yoeEstimated() {
        // Role with only year (no month) — should still compute approximate YOE
        WorkExperience r = new WorkExperience();
        r.setStartDate(LocalDate.of(2020, 1, 1)); // approximated from year
        r.setEndDate(LocalDate.of(2022, 12, 31));
        r.setDatesArePartial(true);

        ResumeSignals signals = new ResumeSignals();
        calculator.compute(List.of(r), null, false, null, null, signals, List.of());
        assertNotNull(signals.getCalculatedYoe());
        assertTrue(signals.getCalculatedYoe() > 1.5);
    }
}
