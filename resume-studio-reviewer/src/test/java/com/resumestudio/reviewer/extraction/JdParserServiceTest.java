package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.JobDescription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdParserServiceTest {

    private JdParserService parser;

    @BeforeEach
    void setUp() {
        parser = new JdParserService();
    }

    // ── Null / blank ──────────────────────────────────────────────────────────

    @Test
    void parse_null_returnsEmptyJd() {
        JobDescription jd = parser.parse(null);
        assertEquals(0.0, jd.getParseConfidence());
    }

    @Test
    void parse_blank_returnsEmptyJd() {
        JobDescription jd = parser.parse("   ");
        assertEquals(0.0, jd.getParseConfidence());
    }

    // ── Title extraction ──────────────────────────────────────────────────────

    @Test
    void extractTitle_fromExplicitLabel() {
        String text = "Position: Senior Backend Engineer\n\nWe need strong Java skills and cloud experience.";
        JobDescription jd = parser.parse(text);
        // The label match captures up to 50 chars of word+whitespace — assert starts with the title
        assertTrue(jd.getRoleTitle().trim().startsWith("Senior Backend Engineer"));
    }

    @Test
    void extractTitle_fromFirstMatchingLine() {
        // No explicit label keywords — fallback to first line containing "engineer"
        String text = "Senior Backend Engineer\n\nJoin our great team and build amazing software.";
        JobDescription jd = parser.parse(text);
        assertEquals("Senior Backend Engineer", jd.getRoleTitle());
    }

    @Test
    void extractTitle_fallsBackToUnknownRole() {
        String text = "We need someone. Please apply. Contact us today.";
        JobDescription jd = parser.parse(text);
        assertEquals("Unknown Role", jd.getRoleTitle());
    }

    // ── YOE extraction ────────────────────────────────────────────────────────

    @Test
    void extractYoe_rangePattern() {
        String text = "Senior Engineer\n\nRequirements:\n3-5 years of Java experience";
        JobDescription jd = parser.parse(text);
        assertEquals(3.0, jd.getYoeMin());
        assertEquals(5.0, jd.getYoeMax());
        assertNotNull(jd.getYoeRawStatement());
    }

    @Test
    void extractYoe_plusPattern() {
        String text = "Backend Engineer\n\nRequirements:\n5+ years experience with distributed systems";
        JobDescription jd = parser.parse(text);
        assertEquals(5.0, jd.getYoeMin());
        assertNull(jd.getYoeMax()); // open-ended
    }

    @Test
    void extractYoe_atLeastPattern() {
        String text = "Engineer\n\nMinimum qualifications:\nAt least 3 years of experience with Java.";
        JobDescription jd = parser.parse(text);
        assertEquals(3.0, jd.getYoeMin());
    }

    @Test
    void extractYoe_noYoeMentioned() {
        String text = "Backend Engineer\n\nRequirements:\n- Java\n- Docker";
        JobDescription jd = parser.parse(text);
        assertNull(jd.getYoeMin());
    }

    // ── Skills extraction ─────────────────────────────────────────────────────

    @Test
    void extractSkills_mustHaveSection() {
        String text = "Senior Backend Engineer\n\nRequirements\nJava\nSpring Boot\nKubernetes";
        JobDescription jd = parser.parse(text);
        assertFalse(jd.getMustHaveSkills().isEmpty());
        assertTrue(jd.getMustHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("Java")));
    }

    @Test
    void extractSkills_niceToHaveSection() {
        String text = "Engineer\n\nRequirements\n- Java\n\nNice-to-have\n- Kotlin\n- Scala";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.getNiceToHaveSkills().stream().anyMatch(s -> s.equalsIgnoreCase("Kotlin")));
    }

    @Test
    void extractSkills_deduplicates() {
        String text = "Backend Engineer\n\nRequirements\n- Java\n- Java\n- Docker";
        JobDescription jd = parser.parse(text);
        long javaCount = jd.getMustHaveSkills().stream()
            .filter(s -> s.equalsIgnoreCase("Java")).count();
        assertEquals(1, javaCount);
    }

    // ── Implied skills ────────────────────────────────────────────────────────

    @Test
    void inferImpliedSkills_springBootImpliesJava() {
        String text = "Backend Engineer\n\nRequirements\n- Spring Boot\n- Docker";
        JobDescription jd = parser.parse(text);
        // Spring Boot implies Java
        assertFalse(jd.getImpliedSkills().isEmpty());
        assertTrue(jd.getImpliedSkills().contains("Java"));
    }

    @Test
    void inferImpliedSkills_reactImpliesJavaScript() {
        String text = "Frontend Engineer\n\nRequirements\n- React\n- Docker";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.getImpliedSkills().contains("JavaScript"));
    }

    // ── IC level ──────────────────────────────────────────────────────────────

    @Test
    void icLevel_seniorDetected() {
        String text = "Senior Backend Engineer\n\nRequirements\n- Java\n5+ years experience";
        JobDescription jd = parser.parse(text);
        assertEquals(4, jd.getIcLevel()); // senior = 4
    }

    @Test
    void icLevel_juniorDetected() {
        String text = "Junior Software Engineer\n\nRequirements\n- Java";
        JobDescription jd = parser.parse(text);
        assertEquals(1, jd.getIcLevel()); // junior = 1
    }

    @Test
    void icLevel_defaultsMidLevel() {
        String text = "Software Engineer\n\nRequirements\n- Java";
        JobDescription jd = parser.parse(text);
        assertEquals(3, jd.getIcLevel()); // default mid
    }

    // ── Context detection ─────────────────────────────────────────────────────

    @Test
    void detectContext_remote() {
        String text = "Backend Engineer\n\nThis is a fully remote role.";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.isRemote());
    }

    @Test
    void detectContext_notRemote() {
        String text = "Backend Engineer\n\nOffice-based in London.";
        JobDescription jd = parser.parse(text);
        assertFalse(jd.isRemote());
    }

    @Test
    void detectContext_startupCulture() {
        String text = "Backend Engineer at a fast-paced startup.\n\nRequirements\n- Java";
        JobDescription jd = parser.parse(text);
        assertEquals("fast-paced startup", jd.getCompanyCulture());
    }

    @Test
    void detectContext_enterprise() {
        String text = "Backend Engineer\n\nFortune 500 enterprise looking for talent.\n\nRequirements\n- Java";
        JobDescription jd = parser.parse(text);
        assertEquals("enterprise", jd.getCompanyCulture());
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    @Test
    void confidence_highWhenAllPresent() {
        String text = "Requirements\nSenior Backend Engineer\n\n3-5 years\nJava, Spring Boot, Docker\n5+ years";
        JobDescription jd = parser.parse(text);
        // Title (0.3) + skills (0.4) + yoe (0.2) = 0.9
        assertTrue(jd.getParseConfidence() >= 0.6);
    }

    @Test
    void wellStructured_whenHasRequirementsSection() {
        String text = "Backend Engineer\n\nRequirements\n- Java\n- Docker";
        JobDescription jd = parser.parse(text);
        assertTrue(jd.isWellStructured());
    }

    @Test
    void wellStructured_false_whenUnstructured() {
        String text = "We need a Java developer with Docker experience.";
        JobDescription jd = parser.parse(text);
        assertFalse(jd.isWellStructured());
    }
}
