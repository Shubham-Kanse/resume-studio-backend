package com.resumestudio.reviewer.extraction;

import com.resumestudio.reviewer.model.enums.SkillsFormat;
import com.resumestudio.reviewer.nlp.SoftSkillsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillsSectionExtractorTest {

    private SkillsSectionExtractor extractor;

    @BeforeEach
    void setUp() {
        SoftSkillsService softSkills = mock(SoftSkillsService.class);
        // Return true for known soft skill words used in tests
        Set<String> knownSoftSkills = Set.of("communication", "teamwork", "leadership",
            "problem solving", "problem-solving", "time management", "adaptability",
            "collaboration", "creativity", "attention to detail");
        when(softSkills.isSoftSkill(anyString())).thenAnswer(inv ->
            knownSoftSkills.contains(((String) inv.getArgument(0)).toLowerCase()));
        extractor = new SkillsSectionExtractor(softSkills);
    }

    // ── null / blank ──────────────────────────────────────────────────────────

    @Test
    void extract_null_returnsNoSection() {
        var result = extractor.extract(null, List.of());
        assertEquals(SkillsFormat.NO_SECTION, result.getFormat());
        assertTrue(result.getSkills().isEmpty());
    }

    @Test
    void extract_blank_returnsNoSection() {
        var result = extractor.extract("   ", List.of());
        assertEquals(SkillsFormat.NO_SECTION, result.getFormat());
    }

    // ── Format detection ──────────────────────────────────────────────────────

    @Test
    void format_flat_commaSeparated() {
        var result = extractor.extract("Java, Python, Go, Docker", List.of());
        assertEquals(SkillsFormat.FLAT_UNORDERED, result.getFormat());
    }

    @Test
    void format_categorised_twoOrMoreCategoryLines() {
        String text = "Programming: Java, Python\nDatabases: PostgreSQL, Redis";
        var result = extractor.extract(text, List.of());
        assertEquals(SkillsFormat.CATEGORISED_UNORDERED, result.getFormat());
    }

    @Test
    void format_bulletList_majorityAreBullets() {
        String text = "• Java\n• Python\n• Docker\n• Kubernetes\nGo";
        var result = extractor.extract(text, List.of());
        assertEquals(SkillsFormat.BULLET_LIST, result.getFormat());
    }

    @Test
    void format_prose_longSentence() {
        String text = "I have extensive experience working with Java and Spring Boot in large distributed microservices environments at scale.";
        var result = extractor.extract(text, List.of());
        assertEquals(SkillsFormat.PROSE, result.getFormat());
    }

    // ── Skill extraction ──────────────────────────────────────────────────────

    @Test
    void extract_commaSeparated_parsesMultipleSkills() {
        var result = extractor.extract("Java, Python, Go", List.of());
        assertEquals(3, result.getSkills().size());
        assertTrue(result.getSkills().stream().anyMatch(s -> s.getRawName().equalsIgnoreCase("Java")));
    }

    @Test
    void extract_categoryLine_assignsCategory() {
        var result = extractor.extract("Programming: Java, Python", List.of());
        assertTrue(result.getSkills().stream()
            .anyMatch(s -> "Programming".equals(s.getCategory())));
    }

    @Test
    void extract_bulletPrefix_strippedCorrectly() {
        var result = extractor.extract("• Java\n- Python\n* Go", List.of());
        assertEquals(3, result.getSkills().size());
        assertTrue(result.getSkills().stream().anyMatch(s -> "Java".equals(s.getRawName())));
    }

    // ── Abbreviation expansion ────────────────────────────────────────────────

    @Test
    void extract_abbreviation_k8sExpandedToKubernetes() {
        var result = extractor.extract("k8s, Java", List.of());
        assertTrue(result.getSkills().stream()
            .anyMatch(s -> s.isAbbreviation() && "Kubernetes".equals(s.getStrippedName())));
    }

    @Test
    void extract_abbreviation_jsExpandedToJavaScript() {
        var result = extractor.extract("js, Python", List.of());
        assertTrue(result.getSkills().stream()
            .anyMatch(s -> s.isAbbreviation() && "JavaScript".equals(s.getStrippedName())));
    }

    // ── Version number ────────────────────────────────────────────────────────

    @Test
    void extract_versionNumber_detected() {
        var result = extractor.extract("Spring Boot 3.2, Java", List.of());
        // "Spring Boot 3.2" should flag hasVersionNumber
        assertTrue(result.getSkills().stream()
            .anyMatch(s -> s.isHasVersionNumber()));
    }

    // ── Soft skills ───────────────────────────────────────────────────────────

    @Test
    void extract_softSkillsOnly_flagged() {
        var result = extractor.extract("Communication, Teamwork, Leadership", List.of());
        assertTrue(result.isHasSoftSkillsOnly());
        assertFalse(result.isHasMixedSoftHard());
        assertEquals(SkillsFormat.GENERIC_ONLY, result.getFormat());
    }

    @Test
    void extract_mixedSoftHard_flagged() {
        var result = extractor.extract("Java, Python, Communication, Teamwork", List.of());
        assertTrue(result.isHasMixedSoftHard());
        assertFalse(result.isHasSoftSkillsOnly());
    }

    // ── Self-rated ────────────────────────────────────────────────────────────

    @Test
    void extract_selfRated_starsDetected() {
        var result = extractor.extract("Java ★★★★, Python ●●○", List.of());
        assertTrue(result.isHasSelfRatedSkills());
        assertEquals(SkillsFormat.SELF_RATED, result.getFormat());
    }

    @Test
    void extract_selfRated_proficiencyWords() {
        var result = extractor.extract("Java (expert), Python (intermediate)", List.of());
        assertTrue(result.isHasSelfRatedSkills());
    }

    // ── Stale technologies ────────────────────────────────────────────────────

    @Test
    void extract_staleSkill_cobol() {
        var result = extractor.extract("COBOL, Java", List.of());
        assertTrue(result.isHasStaleSkills());
        assertTrue(result.getSkills().stream()
            .anyMatch(s -> "STALE".equals(s.getCategory())));
    }

    // ── inSkillsSection flag ──────────────────────────────────────────────────

    @Test
    void extract_allSkillsMarkedInSkillsSection() {
        var result = extractor.extract("Java, Python, Docker", List.of());
        assertTrue(result.getSkills().stream().allMatch(s -> s.isInSkillsSection()));
    }
}
