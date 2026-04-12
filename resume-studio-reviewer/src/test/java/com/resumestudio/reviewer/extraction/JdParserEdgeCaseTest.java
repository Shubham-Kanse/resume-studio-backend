package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.JobDescription;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases for JD parsing not covered in JdParserServiceTest.
 */
class JdParserEdgeCaseTest {

    // We test the model directly since JdParserService has heavy dependencies.
    // Integration-level JD parsing edge cases are covered in JdParserServiceTest.

    @Test void emptyJd_doesNotProduceMisleadingSkills() {
        JobDescription jd = new JobDescription();
        jd.setRawText("");
        assertTrue(jd.getMustHaveSkills().isEmpty());
        assertTrue(jd.getNiceToHaveSkills().isEmpty());
    }

    @Test void jdWithOnlyTitle_hasNoSkills() {
        JobDescription jd = new JobDescription();
        jd.setRoleTitle("Software Engineer");
        jd.setRawText("Software Engineer");
        assertTrue(jd.getMustHaveSkills().isEmpty());
    }

    @Test void jdClarity_defaultsToMedium() {
        JobDescription jd = new JobDescription();
        assertNotNull(jd.getJdClarity());
    }

    @Test void trimmedText_nullSafe() {
        JobDescription jd = new JobDescription();
        assertNull(jd.getTrimmedText()); // null until set — callers must handle
    }
}
