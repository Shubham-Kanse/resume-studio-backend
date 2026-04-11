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
    private SkillMatchEngine engine;

    @BeforeEach
    void setUp() {
        escoGraph = mock(EscoSkillGraph.class);
        // Default: resolve returns the input unchanged, no related skills
        when(escoGraph.resolve(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(escoGraph.relatedSkills(anyString())).thenReturn(List.of());
        engine = new SkillMatchEngine(escoGraph);
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
}
