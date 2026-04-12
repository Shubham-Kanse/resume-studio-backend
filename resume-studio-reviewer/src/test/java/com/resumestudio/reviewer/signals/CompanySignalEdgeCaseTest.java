package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.CompanyTier;
import com.resumestudio.reviewer.model.enums.TitleMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases for company signals — freelance, unknown company, subsidiary names.
 */
class CompanySignalEdgeCaseTest {

    private CompanySignalCalculator calculator;

    @BeforeEach
    void setUp() { calculator = new CompanySignalCalculator(); }

    private WorkExperience role(String company, String descriptor) {
        WorkExperience w = new WorkExperience();
        w.setCompany(company);
        w.setCompanyDescriptor(descriptor);
        w.setStartDate(LocalDate.now().minusYears(2));
        w.setCurrent(true);
        return w;
    }

    @Test void freelance_doesNotCrash() {
        ResumeSignals signals = new ResumeSignals();
        assertDoesNotThrow(() ->
            calculator.compute(List.of(role("Self-employed", null)), "Self-employed", null, signals));
        // Should be UNKNOWN tier — no crash
        assertNotNull(signals.getCurrentCompanyTier());
    }

    @Test void emptyCompanyName_doesNotCrash() {
        ResumeSignals signals = new ResumeSignals();
        assertDoesNotThrow(() ->
            calculator.compute(List.of(role("", null)), "", null, signals));
    }

    @Test void nullCompanyName_doesNotCrash() {
        ResumeSignals signals = new ResumeSignals();
        assertDoesNotThrow(() ->
            calculator.compute(List.of(role(null, null)), null, null, signals));
    }

    @Test void unknownCompany_tiersAsUnknown() {
        ResumeSignals signals = new ResumeSignals();
        calculator.compute(List.of(role("Digital Solutions Ltd", null)), "Digital Solutions Ltd", null, signals);
        assertEquals(CompanyTier.UNKNOWN, signals.getCurrentCompanyTier());
    }

    @Test void companyWithDescriptor_tiersAsDescribed() {
        ResumeSignals signals = new ResumeSignals();
        calculator.compute(
            List.of(role("Acme", "Series B fintech, 200 engineers")),
            "Acme", "Series B fintech, 200 engineers", signals);
        // DESCRIBED tier when descriptor is present
        assertNotEquals(CompanyTier.UNKNOWN, signals.getCurrentCompanyTier());
    }
}
