package com.resumestudio.reviewer.signals;

import com.resumestudio.reviewer.model.ResumeSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FilenameSignalCalculatorTest {

    private FilenameSignalCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new FilenameSignalCalculator();
    }

    @Test
    void professional_nameBasedFilename() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("John_Smith_BackendEngineer.pdf", s);
        assertTrue(s.isFilenameProfessional());
        assertFalse(s.isFilenameGeneric());
        assertFalse(s.isFilenameHasVersioning());
        assertFalse(s.isFilenameTooLong());
        assertTrue(s.isFilenameHasName());
        assertNull(s.getFilenameIssueDetail());
    }

    @Test
    void generic_resumePdf() {
        // GENERIC_NAME requires a non-alpha char after the keyword; "resume-doc" has "-" after "resume"
        ResumeSignals s = new ResumeSignals();
        calculator.compute("resume-doc.pdf", s);
        assertFalse(s.isFilenameProfessional());
        assertTrue(s.isFilenameGeneric());
        assertNotNull(s.getFilenameIssueDetail());
    }

    @Test
    void generic_myCV() {
        // "my_cv_updated" — "my_cv" followed by "_" (non-alpha) triggers GENERIC_NAME
        ResumeSignals s = new ResumeSignals();
        calculator.compute("my_cv_updated.pdf", s);
        assertFalse(s.isFilenameProfessional());
        assertTrue(s.isFilenameGeneric());
    }

    @Test
    void versioning_finalInName() {
        // Hyphen before "final" creates a \b word boundary for the VERSIONING pattern
        ResumeSignals s = new ResumeSignals();
        calculator.compute("John-Smith-final.pdf", s);
        assertFalse(s.isFilenameProfessional());
        assertTrue(s.isFilenameHasVersioning());
        assertNotNull(s.getFilenameIssueDetail());
    }

    @Test
    void versioning_v2InName() {
        // Hyphen creates \b boundary so \bv2\b matches
        ResumeSignals s = new ResumeSignals();
        calculator.compute("John-Smith-v2.pdf", s);
        assertFalse(s.isFilenameProfessional());
        assertTrue(s.isFilenameHasVersioning());
    }

    @Test
    void tooLong_exceedsLimit() {
        // Name longer than 40 chars without extension
        ResumeSignals s = new ResumeSignals();
        calculator.compute("John_Smith_Senior_Backend_Software_Engineer_2024.pdf", s);
        assertFalse(s.isFilenameProfessional());
        assertTrue(s.isFilenameTooLong());
    }

    @Test
    void noName_randomChars() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("document_xyz123.pdf", s);
        assertFalse(s.isFilenameProfessional());
        // isFilenameHasName uses pattern [A-Z][a-z]+[_\-. ]?[A-Z][a-z]+
        assertFalse(s.isFilenameHasName());
    }

    @Test
    void nullFilename_setsNotProfessionalAndGeneric() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute(null, s);
        assertFalse(s.isFilenameProfessional());
        assertTrue(s.isFilenameGeneric());
        assertNotNull(s.getFilenameIssueDetail());
    }

    @Test
    void blankFilename_setsNotProfessionalAndGeneric() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("  ", s);
        assertFalse(s.isFilenameProfessional());
        assertTrue(s.isFilenameGeneric());
    }

    @Test
    void camelCase_nameDetected() {
        ResumeSignals s = new ResumeSignals();
        calculator.compute("JaneSmith_SoftwareEngineer.pdf", s);
        assertTrue(s.isFilenameHasName());
    }
}
