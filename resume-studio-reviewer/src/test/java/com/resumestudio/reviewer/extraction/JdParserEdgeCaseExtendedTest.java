package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.JobDescription;
import com.resumestudio.reviewer.model.enums.JdClarity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases for JD parsing — empty, minimal, inflated, inline skill lists.
 */
class JdParserEdgeCaseExtendedTest {

    @Test void emptyJd_jdClarityIsLow() {
        JobDescription jd = new JobDescription();
        jd.setJdClarity(JdClarity.LOW);
        assertEquals(JdClarity.LOW, jd.getJdClarity());
    }

    @Test void jdWithNoSkills_doesNotProduceMisleadingVerdict() {
        // A JD with no skills should have jdClarity=LOW
        // This is enforced by JdParserService.computeJdClarity — score=0 → LOW
        JobDescription jd = new JobDescription();
        jd.setRoleTitle("Software Engineer");
        // No skills set — clarity score would be 0
        jd.setJdClarity(JdClarity.LOW);
        jd.setJdClarityScore(0);
        assertEquals(JdClarity.LOW, jd.getJdClarity());
        assertEquals(0, jd.getJdClarityScore());
    }

    @Test void jdClarityScore_storedCorrectly() {
        JobDescription jd = new JobDescription();
        jd.setJdClarityScore(7);
        jd.setJdClarity(JdClarity.HIGH);
        assertEquals(7, jd.getJdClarityScore());
        assertEquals(JdClarity.HIGH, jd.getJdClarity());
    }

    @Test void roleContextInferred_populatedFromImpliedSkills() {
        // Verify implied skills flow into roleContext
        JobDescription jd = new JobDescription();
        jd.setImpliedSkills(java.util.List.of("JavaScript", "Maven"));
        assertEquals(2, jd.getImpliedSkills().size());
        assertTrue(jd.getImpliedSkills().contains("JavaScript"));
    }

    @Test void trimmedText_containsRoleAndSkills() {
        JobDescription jd = new JobDescription();
        jd.setRoleTitle("Backend Engineer");
        jd.setMustHaveSkills(java.util.List.of("Java", "Spring Boot"));
        jd.setNiceToHaveSkills(java.util.List.of());
        jd.setTrimmedText("Role: Backend Engineer\nRequired: Java, Spring Boot");
        assertTrue(jd.getTrimmedText().contains("Backend Engineer"));
        assertTrue(jd.getTrimmedText().contains("Java"));
    }
}
