package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.Resume;
import com.resumestudio.reviewer.skills.EscoSkillGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SummaryExtractorTest {

    private SummaryExtractor extractor;
    private EscoSkillGraph escoGraph;

    @BeforeEach
    void setUp() {
        escoGraph = mock(EscoSkillGraph.class);
        // Mock common technical skills
        when(escoGraph.isKnownSkill("java")).thenReturn(true);
        when(escoGraph.isKnownSkill("spring")).thenReturn(true);
        when(escoGraph.isKnownSkill("spring boot")).thenReturn(true);
        when(escoGraph.isKnownSkill("kubernetes")).thenReturn(true);
        when(escoGraph.isKnownSkill("react")).thenReturn(true);
        when(escoGraph.isKnownSkill("node")).thenReturn(true);
        when(escoGraph.isKnownSkill("node.js")).thenReturn(true);
        when(escoGraph.isKnownSkill("postgresql")).thenReturn(true);
        extractor = new SummaryExtractor(escoGraph);
    }

    // ── extract() ────────────────────────────────────────────────────────────

    @Test
    void extract_setsText() {
        Resume resume = new Resume();
        extractor.extract("  Senior Backend Engineer with 7 years of experience.  ", resume);
        assertEquals("Senior Backend Engineer with 7 years of experience.", resume.getSummaryText());
    }

    @Test
    void extract_nullInput_setsNull() {
        Resume resume = new Resume();
        extractor.extract(null, resume);
        assertNull(resume.getSummaryText());
    }

    @Test
    void extract_blankInput_setsNull() {
        Resume resume = new Resume();
        extractor.extract("   ", resume);
        assertNull(resume.getSummaryText());
    }

    // ── analyse() — not present ───────────────────────────────────────────────

    @Test
    void analyse_absent_whenNull() {
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(null, "Backend Engineer");
        assertFalse(a.isPresent());
    }

    @Test
    void analyse_absent_whenBlank() {
        SummaryExtractor.SummaryAnalysis a = extractor.analyse("  ", "Backend Engineer");
        assertFalse(a.isPresent());
    }

    // ── analyse() — present ───────────────────────────────────────────────────

    @Test
    void analyse_strong_allSignalsPresent() {
        String summary = "Senior Backend Engineer with 6 years of Java, Spring Boot, and Kubernetes experience.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, "Backend Engineer");
        assertTrue(a.isPresent());
        assertTrue(a.isMentionsYoe());
        assertTrue(a.isMentionsSkills());
        assertFalse(a.isGeneric());
        assertTrue(a.isStrong());
    }

    @Test
    void analyse_mentionsYoe_extractsNumber() {
        String summary = "Engineer with 5+ years of professional experience in distributed systems.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertTrue(a.isMentionsYoe());
    }

    @Test
    void analyse_doesNotMentionYoe() {
        String summary = "Software engineer passionate about building scalable systems.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertFalse(a.isMentionsYoe());
    }

    @Test
    void analyse_mentionsSkills_detectsTechTerms() {
        String summary = "Experienced in React, Node.js, and PostgreSQL.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertTrue(a.isMentionsSkills());
    }

    @Test
    void analyse_noTechTerms_skillsFalse() {
        String summary = "Experienced professional with strong communication skills.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertFalse(a.isMentionsSkills());
    }

    @Test
    void analyse_generic_teamPlayerPhrase() {
        String summary = "Passionate developer and team player who loves coding.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertTrue(a.isGeneric());
        assertFalse(a.isStrong());
    }

    @Test
    void analyse_generic_resultsDriven() {
        String summary = "Results-driven engineer with deep knowledge of cloud.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertTrue(a.isGeneric());
    }

    @Test
    void analyse_mentionsTitle_whenJdTitleWordsPresent() {
        // jdTitle = "Backend Engineer" — summary contains "backend" and "engineer"
        String summary = "Senior Backend Engineer with 5 years building APIs.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, "Backend Engineer");
        assertTrue(a.isMentionsTitle());
    }

    @Test
    void analyse_doesNotMentionTitle_whenJdTitleAbsent() {
        String summary = "Experienced DevOps professional focused on Kubernetes.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, "Mobile Engineer");
        assertFalse(a.isMentionsTitle());
    }

    @Test
    void analyse_nullJdTitle_doesNotCrash() {
        String summary = "Backend engineer with 5 years experience in Java.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertTrue(a.isPresent());
        // mentionsTitle stays false when jdTitle null
        assertFalse(a.isMentionsTitle());
    }

    @Test
    void analyse_notStrong_whenGeneric() {
        String summary = "Passionate team player with 5 years of Java experience.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertFalse(a.isStrong()); // generic flag kills strong
    }

    @Test
    void analyse_notStrong_whenNoYoe() {
        String summary = "Senior Backend Engineer with Java and Spring Boot skills.";
        SummaryExtractor.SummaryAnalysis a = extractor.analyse(summary, null);
        assertFalse(a.isStrong()); // missing YOE kills strong
    }
}
