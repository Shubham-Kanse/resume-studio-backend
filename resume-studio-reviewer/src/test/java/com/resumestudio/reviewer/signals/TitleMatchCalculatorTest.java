package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.extraction.DesignationOntologyService;
import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.TitleMatch;
import com.resumestudio.reviewer.model.enums.TitleProgression;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TitleMatchCalculatorTest {

    private TitleMatchCalculator calculator;

    @BeforeEach
    void setUp() {
        DesignationOntologyService ontology = mock(DesignationOntologyService.class);
        when(ontology.canonicalise(anyString())).thenReturn(null);
        when(ontology.domains(anyString())).thenReturn(List.of());
        when(ontology.seniorityLevel(anyString())).thenReturn(3);
        when(ontology.relatedDesignations(anyString())).thenReturn(List.of());
        // For adjacent test: "Senior API Developer" and "Staff Platform Engineer" share "Backend" domain
        when(ontology.domains("Senior API Developer")).thenReturn(List.of("Backend"));
        when(ontology.domains("Staff Platform Engineer")).thenReturn(List.of("Backend", "DevOps"));
        when(ontology.seniorityLevel("Senior API Developer")).thenReturn(4);
        when(ontology.seniorityLevel("Staff Platform Engineer")).thenReturn(5);
        calculator = new TitleMatchCalculator(ontology);
    }

    // ── TitleMatch ─────────────────────────────────────────────────────────────

    @Test
    void titleMatch_exact_identicalTitles() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Backend Engineer", "Backend Engineer", List.of(), s);
        assertEquals(TitleMatch.EXACT, s.getTitleMatch());
    }

    @Test
    void titleMatch_exact_seniorityCoreMatch() {
        // "Senior Backend Engineer" vs "Junior Backend Engineer" — core = "backend engineer"
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Senior Backend Engineer", "Junior Backend Engineer", List.of(), s);
        assertEquals(TitleMatch.EXACT, s.getTitleMatch());
    }

    @Test
    void titleMatch_adjacent_samedomainCloseLevel() {
        // Both in backend domain (api & platform are backend keywords), IC levels differ by 1 (4 vs 5).
        // Seniority-stripped cores differ ("api developer" vs "platform engineer") so EXACT is not triggered.
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Senior API Developer", "Staff Platform Engineer", List.of(), s);
        assertEquals(TitleMatch.ADJACENT, s.getTitleMatch());
    }

    @Test
    void titleMatch_related_overlappingDomainWord() {
        // "Backend Developer" vs "Backend Architect" — share "backend" keyword (>3 chars)
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Backend Developer", "Backend Architect", List.of(), s);
        // "backend" is > 3 chars and present in both → RELATED at minimum
        assertNotEquals(TitleMatch.MISS, s.getTitleMatch());
    }

    @Test
    void titleMatch_miss_completelyUnrelated() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Marketing Manager", "Backend Engineer", List.of(), s);
        assertEquals(TitleMatch.MISS, s.getTitleMatch());
    }

    @Test
    void titleMatch_miss_nullCandidate() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute(null, "Backend Engineer", List.of(), s);
        assertEquals(TitleMatch.MISS, s.getTitleMatch());
    }

    @Test
    void titleMatch_miss_nullJd() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Backend Engineer", null, List.of(), s);
        assertEquals(TitleMatch.MISS, s.getTitleMatch());
    }

    // ── TitleProgression ───────────────────────────────────────────────────────

    @Test
    void progression_growing_juniorToSenior() {
        WorkExperience e1 = role("Junior Software Engineer");
        WorkExperience e2 = role("Software Engineer");
        WorkExperience e3 = role("Senior Software Engineer");
        ResumeSignals s = new ResumeSignals();
        // experience list is most-recent-first (reversed in compute)
        calculator.compute("Senior Software Engineer", "Senior Engineer", List.of(e3, e2, e1), s);
        assertEquals(TitleProgression.GROWING, s.getTitleProgression());
    }

    @Test
    void progression_regression_seniorToJunior() {
        WorkExperience e1 = role("Senior Engineer");
        WorkExperience e2 = role("Junior Engineer");
        ResumeSignals s = new ResumeSignals();
        // Most-recent-first: e2 is most recent
        calculator.compute("Junior Engineer", "Engineer", List.of(e2, e1), s);
        assertEquals(TitleProgression.REGRESSION, s.getTitleProgression());
    }

    @Test
    void progression_flat_sameLevel() {
        WorkExperience e1 = role("Software Engineer");
        WorkExperience e2 = role("Software Developer"); // both mid-level (default 3)
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Developer", "Engineer", List.of(e2, e1), s);
        assertEquals(TitleProgression.FLAT, s.getTitleProgression());
    }

    @Test
    void progression_unknown_singleRole() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Backend Engineer", "Backend Engineer", List.of(role("Backend Engineer")), s);
        assertEquals(TitleProgression.UNKNOWN, s.getTitleProgression());
    }

    @Test
    void progression_unknown_emptyExperience() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Backend Engineer", "Backend Engineer", List.of(), s);
        assertEquals(TitleProgression.UNKNOWN, s.getTitleProgression());
    }

    @Test
    void compute_setsCandidateAndJdTitle() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("Backend Engineer", "Senior Backend Engineer", List.of(), s);
        assertEquals("Backend Engineer", s.getCandidateTitle());
        assertEquals("Senior Backend Engineer", s.getJdTitle());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkExperience role(String title) {
        WorkExperience e = new WorkExperience();
        e.setTitle(title);
        return e;
    }
}
