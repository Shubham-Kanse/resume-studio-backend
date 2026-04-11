package com.resumestudio.reviewer.extraction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticExtractorTest {

    private SemanticExtractor extractor;

    @BeforeEach
    void setUp() { extractor = new SemanticExtractor(); }

    // ── classifyHeader ────────────────────────────────────────────────────────

    @Test void classifyHeader_exactLowercase() {
        assertEquals(SemanticExtractor.SectionType.EXPERIENCE, extractor.classifyHeader("experience"));
    }

    @Test void classifyHeader_allCaps() {
        assertEquals(SemanticExtractor.SectionType.SKILLS, extractor.classifyHeader("TECHNICAL SKILLS"));
    }

    @Test void classifyHeader_withColon() {
        assertEquals(SemanticExtractor.SectionType.EDUCATION, extractor.classifyHeader("Education:"));
    }

    @Test void classifyHeader_withDash() {
        assertEquals(SemanticExtractor.SectionType.EXPERIENCE, extractor.classifyHeader("Work Experience —"));
    }

    @Test void classifyHeader_unknown_longLine() {
        assertEquals(SemanticExtractor.SectionType.UNKNOWN,
            extractor.classifyHeader("I am a software engineer with 5 years of experience building systems"));
    }

    @Test void classifyHeader_projects() {
        assertEquals(SemanticExtractor.SectionType.PROJECTS, extractor.classifyHeader("Personal Projects"));
    }

    @Test void classifyHeader_certifications() {
        assertEquals(SemanticExtractor.SectionType.CERTIFICATIONS, extractor.classifyHeader("Certifications"));
    }

    // ── extractYoe ────────────────────────────────────────────────────────────

    @Test void extractYoe_explicit() {
        assertEquals(3.5, extractor.extractYoe("3.5+ years delivering backend systems"), 0.01);
    }

    @Test void extractYoe_wholeNumber() {
        assertEquals(5.0, extractor.extractYoe("5 years of professional experience"), 0.01);
    }

    @Test void extractYoe_negativeContext_ago() {
        assertNull(extractor.extractYoe("founded 10 years ago"));
    }

    @Test void extractYoe_negativeContext_since() {
        assertNull(extractor.extractYoe("since 3 years the company has grown"));
    }

    @Test void extractYoe_null() {
        assertNull(extractor.extractYoe(null));
    }

    @Test void extractYoe_blank() {
        assertNull(extractor.extractYoe("   "));
    }

    @Test void extractYoe_outOfRange() {
        assertNull(extractor.extractYoe("60 years of experience")); // > 50
    }

    @Test void extractYoe_picksHighestScoringCandidate() {
        // "5 years of professional experience" should win over "2 years ago"
        Double yoe = extractor.extractYoe("2 years ago I started. Now I have 5 years of professional experience.");
        assertEquals(5.0, yoe, 0.01);
    }

    // ── extractSections ───────────────────────────────────────────────────────

    @Test void extractSections_parsesAllSections() {
        String text = """
            Summary
            Experienced engineer.
            
            Experience
            Software Engineer at Acme, 2020-2023
            
            Skills
            Java, Spring Boot
            
            Education
            B.Sc Computer Science
            """;
        SemanticExtractor.SectionMap map = extractor.extractSections(text);
        assertNotNull(map.summary);
        assertNotNull(map.experience);
        assertNotNull(map.skills);
        assertNotNull(map.education);
    }

    @Test void extractSections_noHeaders_fallsBackToFullText() {
        String text = "Software Engineer at Acme 2020-2023. Built microservices.";
        SemanticExtractor.SectionMap map = extractor.extractSections(text);
        assertNotNull(map.experience);
    }

    @Test void extractSections_nullInput() {
        SemanticExtractor.SectionMap map = extractor.extractSections(null);
        assertNull(map.experience);
    }
}
