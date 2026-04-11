package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.SkillMatchType;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillMatchEngineTest {

    private EscoSkillGraph escoGraph;
    private SemanticSkillMatcher semanticMatcher;
    private SkillMatchEngine engine;

    @BeforeEach
    void setUp() {
        escoGraph = mock(EscoSkillGraph.class);
        semanticMatcher = mock(SemanticSkillMatcher.class);
        // Default: resolve returns the input unchanged, no related skills
        when(escoGraph.resolve(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(escoGraph.relatedSkills(anyString())).thenReturn(List.of());
        when(semanticMatcher.findBestMatch(anyString(), anyList())).thenReturn(null);
        engine = new SkillMatchEngine(escoGraph, semanticMatcher);
    }

    private Skill skill(String name) {
        Skill s = new Skill(name);
        s.setVisibility(SkillVisibility.SURFACE);
        return s;
    }

    // ── EXACT ─────────────────────────────────────────────────────────────────

    @Test void match_exact_caseInsensitive() {
        SkillMatchResult r = engine.match("Java", List.of(skill("java")), true);
        assertEquals(SkillMatchType.EXACT, r.getMatchType());
        assertTrue(r.isMatched());
    }

    @Test void match_exact_withSpaces() {
        SkillMatchResult r = engine.match("Spring Boot", List.of(skill("Spring Boot")), true);
        assertEquals(SkillMatchType.EXACT, r.getMatchType());
    }

    // ── SYNONYM ───────────────────────────────────────────────────────────────

    @Test void match_synonym_viaEsco() {
        when(escoGraph.resolve("PostgreSQL")).thenReturn("postgresql");
        when(escoGraph.resolve("Postgres")).thenReturn("postgresql");
        SkillMatchResult r = engine.match("PostgreSQL", List.of(skill("Postgres")), true);
        assertEquals(SkillMatchType.SYNONYM, r.getMatchType());
    }

    // ── VERSION_STRIPPED ──────────────────────────────────────────────────────

    @Test void match_versionStripped() {
        Skill s = skill("Java 17");
        s.setHasVersionNumber(true);
        s.setStrippedName("Java");
        SkillMatchResult r = engine.match("Java", List.of(s), true);
        assertEquals(SkillMatchType.VERSION_STRIPPED, r.getMatchType());
    }

    // ── PARENT_FRAMEWORK ──────────────────────────────────────────────────────

    @Test void match_parentFramework_jdHasChild_resumeHasParent() {
        // JD: "Spring Boot", resume has "Spring"
        SkillMatchResult r = engine.match("Spring Boot", List.of(skill("Spring")), false);
        assertEquals(SkillMatchType.PARENT_FRAMEWORK, r.getMatchType());
    }

    // ── IMPLICIT ──────────────────────────────────────────────────────────────

    @Test void match_implicit_viaRelatedSkill() {
        when(escoGraph.relatedSkills("spring boot")).thenReturn(List.of("Java"));
        Skill s = skill("Spring Boot");
        s.setCanonicalName("spring boot");
        SkillMatchResult r = engine.match("Java", List.of(s), false);
        assertEquals(SkillMatchType.IMPLICIT, r.getMatchType());
        assertEquals(SkillVisibility.MISSING, r.getVisibility()); // implicit = not surfaced
    }

    // ── MISSING ───────────────────────────────────────────────────────────────

    @Test void match_missing_whenNoStrategyMatches() {
        SkillMatchResult r = engine.match("Kubernetes", List.of(skill("Java")), true);
        assertEquals(SkillMatchType.MISSING, r.getMatchType());
        assertFalse(r.isMatched());
    }

    // ── matchAll ──────────────────────────────────────────────────────────────

    @Test void matchAll_returnsResultForEachJdSkill() {
        List<SkillMatchResult> results = engine.matchAll(
            List.of("Java", "Kubernetes"),
            List.of(skill("Java")),
            true
        );
        assertEquals(2, results.size());
        assertEquals(SkillMatchType.EXACT, results.get(0).getMatchType());
        assertEquals(SkillMatchType.MISSING, results.get(1).getMatchType());
    }

    @Test void matchAll_emptyJdSkills_returnsEmpty() {
        assertTrue(engine.matchAll(List.of(), List.of(skill("Java")), true).isEmpty());
    }

    // ── Null Safety ───────────────────────────────────────────────────────────

    @Test void matchAll_nullJdSkills_returnsEmpty() {
        assertTrue(engine.matchAll(null, List.of(skill("Java")), true).isEmpty());
    }

    @Test void match_nullResumeSkills_returnsMissing() {
        SkillMatchResult r = engine.match("Java", null, true);
        assertEquals(SkillMatchType.MISSING, r.getMatchType());
        assertFalse(r.isMatched());
    }

    @Test void match_emptyResumeSkills_returnsMissing() {
        SkillMatchResult r = engine.match("Java", List.of(), true);
        assertEquals(SkillMatchType.MISSING, r.getMatchType());
        assertFalse(r.isMatched());
    }

    @Test void match_nullJdSkill_returnsMissing() {
        SkillMatchResult r = engine.match(null, List.of(skill("Java")), true);
        assertEquals(SkillMatchType.MISSING, r.getMatchType());
        assertFalse(r.isMatched());
    }

    @Test void match_blankJdSkill_returnsMissing() {
        SkillMatchResult r = engine.match("  ", List.of(skill("Java")), true);
        assertEquals(SkillMatchType.MISSING, r.getMatchType());
        assertFalse(r.isMatched());
    }

    // ── PARENT_FRAMEWORK edge cases ───────────────────────────────────────────

    @Test void match_parentFramework_doesNotMatchJavaInJavaScript() {
        // "JavaScript" should NOT match "Java" via PARENT_FRAMEWORK
        SkillMatchResult r = engine.match("JavaScript", List.of(skill("Java")), false);
        assertNotEquals(SkillMatchType.PARENT_FRAMEWORK, r.getMatchType());
    }

    @Test void match_parentFramework_doesNotMatchScriptInTypeScript() {
        // "TypeScript" should NOT match "Script" via PARENT_FRAMEWORK
        SkillMatchResult r = engine.match("TypeScript", List.of(skill("Script")), false);
        assertNotEquals(SkillMatchType.PARENT_FRAMEWORK, r.getMatchType());
    }

    @Test void match_parentFramework_matchesSpringInSpringBoot() {
        // "Spring Boot" SHOULD match "Spring" via PARENT_FRAMEWORK
        SkillMatchResult r = engine.match("Spring Boot", List.of(skill("Spring")), false);
        assertEquals(SkillMatchType.PARENT_FRAMEWORK, r.getMatchType());
    }

    // ── Null skill names ──────────────────────────────────────────────────────

    @Test void match_resumeSkillWithNullRawName_skipsSkill() {
        Skill nullSkill = new Skill(null);
        Skill validSkill = skill("Java");
        SkillMatchResult r = engine.match("Java", List.of(nullSkill, validSkill), true);
        assertEquals(SkillMatchType.EXACT, r.getMatchType());
        assertEquals("Java", r.getResumeSkill());
    }

    @Test void match_allResumeSkillsHaveNullRawName_returnsMissing() {
        Skill nullSkill1 = new Skill(null);
        Skill nullSkill2 = new Skill(null);
        SkillMatchResult r = engine.match("Java", List.of(nullSkill1, nullSkill2), true);
        assertEquals(SkillMatchType.MISSING, r.getMatchType());
    }
}
