package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SentenceBankTest {

    private SentenceBank bank;

    @BeforeEach
    void setUp() {
        bank = new SentenceBank();
    }

    // ── Filename sentences ────────────────────────────────────────────────────

    @Test
    void filenameObservation_professional() {
        ResumeSignals s = new ResumeSignals();
        s.setFilenameProfessional(true);
        assertNotNull(bank.filenameObservation(s));
        assertTrue(bank.filenameObservation(s).contains("professional"));
    }

    @Test
    void filenameObservation_generic() {
        ResumeSignals s = new ResumeSignals();
        s.setFilenameProfessional(false);
        s.setFilenameGeneric(true);
        assertTrue(bank.filenameObservation(s).contains("generic"));
    }

    @Test
    void filenameObservation_versioning() {
        ResumeSignals s = new ResumeSignals();
        s.setFilenameProfessional(false);
        s.setFilenameGeneric(false);
        s.setFilenameHasVersioning(true);
        assertTrue(bank.filenameObservation(s).contains("versioning"));
    }

    @Test
    void filenameAction_returnsTemplate() {
        assertNotNull(bank.filenameAction());
        assertTrue(bank.filenameAction().contains("FirstName"));
    }

    // ── Title sentences ───────────────────────────────────────────────────────

    @Test
    void titleObservation_exact() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        s.setCandidateTitle("Backend Engineer");
        s.setJdTitle("Backend Engineer");
        assertTrue(bank.titleObservation(s).contains("exact"));
    }

    @Test
    void titleObservation_miss() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.MISS);
        s.setCandidateTitle("Marketing Manager");
        s.setJdTitle("Backend Engineer");
        assertTrue(bank.titleObservation(s).contains("doesn't immediately read"));
    }

    @Test
    void titleAction_null_forExactMatch() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        assertNull(bank.titleAction(s));
    }

    @Test
    void titleAction_notNull_forMiss() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.MISS);
        s.setJdTitle("Backend Engineer");
        assertNotNull(bank.titleAction(s));
    }

    // ── Summary sentences ─────────────────────────────────────────────────────

    @Test
    void summaryObservation_notPresent() {
        ResumeSignals s = new ResumeSignals();
        s.setSummaryPresent(false);
        assertTrue(bank.summaryObservation(s).contains("No summary"));
    }

    @Test
    void summaryObservation_generic() {
        ResumeSignals s = new ResumeSignals();
        s.setSummaryPresent(true);
        s.setSummaryIsGeneric(true);
        assertTrue(bank.summaryObservation(s).contains("soft skill"));
    }

    @Test
    void summaryAction_notPresent_returnsTemplate() {
        ResumeSignals s = new ResumeSignals();
        s.setSummaryPresent(false);
        assertNotNull(bank.summaryAction(s));
        assertTrue(bank.summaryAction(s).contains("2-line summary"));
    }

    @Test
    void summaryAction_goodSummary_returnsNull() {
        ResumeSignals s = new ResumeSignals();
        s.setSummaryPresent(true);
        s.setSummaryIsGeneric(false);
        assertNull(bank.summaryAction(s));
    }

    // ── YOE sentences ─────────────────────────────────────────────────────────

    @Test
    void yoeObservation_inRange() {
        ResumeSignals s = new ResumeSignals();
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setCalculatedYoe(5.0);
        s.setJdYoeMin(3.0);
        s.setJdYoeMax(7.0);
        s.setYoeState(YoeState.EXPLICIT);
        assertNotNull(bank.yoeObservation(s));
    }

    @Test
    void yoeObservation_cannotDetermine_missing() {
        ResumeSignals s = new ResumeSignals();
        s.setYoeFit(YoeFit.CANNOT_DETERMINE);
        s.setYoeState(YoeState.MISSING);
        assertTrue(bank.yoeObservation(s).contains("No experience dates"));
    }

    @Test
    void yoeAction_vagueState_returnsAction() {
        ResumeSignals s = new ResumeSignals();
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setYoeState(YoeState.VAGUE);
        assertNotNull(bank.yoeAction(s));
    }

    @Test
    void yoeAction_inRange_explicit_returnsNull() {
        ResumeSignals s = new ResumeSignals();
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setYoeState(YoeState.EXPLICIT);
        assertNull(bank.yoeAction(s));
    }

    // ── Skills visibility sentences ───────────────────────────────────────────

    @Test
    void skillVisibilityObservation_surface() {
        SkillMatchResult r = new SkillMatchResult("Java", true);
        r.setVisibility(SkillVisibility.SURFACE);
        assertTrue(bank.skillVisibilityObservation(r).contains("visible"));
    }

    @Test
    void skillVisibilityObservation_missing() {
        SkillMatchResult r = new SkillMatchResult("Java", true);
        r.setVisibility(SkillVisibility.MISSING);
        assertTrue(bank.skillVisibilityObservation(r).contains("does not appear"));
    }

    @Test
    void skillVisibilityAction_surface_returnsNull() {
        SkillMatchResult r = new SkillMatchResult("Java", true);
        r.setVisibility(SkillVisibility.SURFACE);
        assertNull(bank.skillVisibilityAction(r));
    }

    @Test
    void skillVisibilityAction_buried_returnsAction() {
        SkillMatchResult r = new SkillMatchResult("Java", true);
        r.setVisibility(SkillVisibility.BURIED);
        assertNotNull(bank.skillVisibilityAction(r));
    }

    // ── Company sentences ─────────────────────────────────────────────────────

    @Test
    void companyObservation_faang() {
        ResumeSignals s = new ResumeSignals();
        s.setCurrentCompanyTier(CompanyTier.FAANG);
        s.setCurrentCompanyName("Google");
        assertTrue(bank.companyObservation(s).contains("top-tier"));
    }

    @Test
    void companyAction_unknown_returnsDescriptorAdvice() {
        ResumeSignals s = new ResumeSignals();
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        assertNotNull(bank.companyAction(s));
        assertTrue(bank.companyAction(s).contains("descriptor"));
    }

    @Test
    void companyAction_faang_returnsNull() {
        ResumeSignals s = new ResumeSignals();
        s.setCurrentCompanyTier(CompanyTier.FAANG);
        assertNull(bank.companyAction(s));
    }

    // ── Gap sentences ─────────────────────────────────────────────────────────

    @Test
    void gapObservation_noDescriptions_genericMessage() {
        ResumeSignals s = new ResumeSignals();
        assertTrue(bank.gapObservation(s).contains("gap"));
    }

    @Test
    void gapAction_returnsTemplate() {
        assertNotNull(bank.gapAction());
        assertTrue(bank.gapAction().contains("Label career gaps"));
    }

    // ── Skills format sentences ───────────────────────────────────────────────

    @Test
    void skillsFormatObservation_allFormats_neverNull() {
        for (SkillsFormat format : SkillsFormat.values()) {
            ResumeSignals s = new ResumeSignals();
            s.setSkillsFormat(format);
            assertNotNull(bank.skillsFormatObservation(s), "Format: " + format);
        }
    }

    @Test
    void skillsFormatAction_optimalAndFlatOrdered_returnsNull() {
        ResumeSignals s1 = new ResumeSignals();
        s1.setSkillsFormat(SkillsFormat.OPTIMAL);
        assertNull(bank.skillsFormatAction(s1));

        ResumeSignals s2 = new ResumeSignals();
        s2.setSkillsFormat(SkillsFormat.FLAT_ORDERED);
        assertNull(bank.skillsFormatAction(s2));
    }
}
