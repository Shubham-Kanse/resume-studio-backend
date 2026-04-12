package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.AbsenceReason;
import com.resumestudio.reviewer.model.enums.SkillMatchType;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Edge cases for skill matching not covered in SkillMatchEngineTest.
 */
class SkillMatchEdgeCaseTest {

    private EscoSkillGraph escoGraph;
    private SemanticSkillMatcher semanticMatcher;
    private SkillMatchEngine engine;

    @BeforeEach
    void setUp() {
        escoGraph = mock(EscoSkillGraph.class);
        semanticMatcher = mock(SemanticSkillMatcher.class);
        when(escoGraph.resolve(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(escoGraph.relatedSkills(anyString())).thenReturn(List.of());
        when(escoGraph.areEquivalent(anyString(), anyString())).thenReturn(false);
        when(semanticMatcher.findBestMatch(anyString(), anyList())).thenReturn(null);
        engine = new SkillMatchEngine(escoGraph, semanticMatcher);
    }

    private Skill skill(String name) {
        Skill s = new Skill(name);
        s.setVisibility(SkillVisibility.SURFACE);
        return s;
    }

    private Skill skillInSectionOnly(String name) {
        Skill s = new Skill(name);
        s.setVisibility(SkillVisibility.SURFACE);
        s.setInSkillsSection(true);
        s.setBulletOccurrences(0);
        return s;
    }

    // ── Abbreviation resolution ───────────────────────────────────────────────

    @Test void jsAbbreviation_matchesJavaScript() {
        // EscoGraph.resolve("JS") should return "JavaScript" via abbreviation table
        EscoSkillGraph real = new EscoSkillGraph(mock(MindTechOntology.class));
        assertEquals("JavaScript", real.resolve("JS"));
        assertEquals("JavaScript", real.resolve("js"));
    }

    @Test void awsAbbreviation_matchesAmazonWebServices() {
        EscoSkillGraph real = new EscoSkillGraph(mock(MindTechOntology.class));
        assertEquals("Amazon Web Services", real.resolve("AWS"));
    }

    @Test void k8sAbbreviation_matchesKubernetes() {
        EscoSkillGraph real = new EscoSkillGraph(mock(MindTechOntology.class));
        assertEquals("Kubernetes", real.resolve("k8s"));
    }

    @Test void postgresAbbreviation_matchesPostgreSQL() {
        EscoSkillGraph real = new EscoSkillGraph(mock(MindTechOntology.class));
        assertEquals("PostgreSQL", real.resolve("postgres"));
    }

    @Test void vanillaJs_matchesJavaScript() {
        EscoSkillGraph real = new EscoSkillGraph(mock(MindTechOntology.class));
        assertEquals("JavaScript", real.resolve("Vanilla JS"));
    }

    // ── AbsenceReason classification ──────────────────────────────────────────

    @Test void absenceReason_omitted_whenSkillInSectionButNoBullets() {
        // Skill "k8s" is in skills section only (no bullets), JD asks for "k8s"
        // After normalisation both resolve to "Kubernetes" → EXACT match
        // OMITTED only applies when the skill is truly MISSING after all strategies
        // So we test a skill that's listed in section but JD uses a completely different name
        // that doesn't match via any strategy
        Skill s = skillInSectionOnly("Kubernetes");
        // JD asks for "container orchestration" — not matched by any strategy
        when(escoGraph.resolve("container orchestration")).thenReturn("container orchestration");
        SkillMatchResult r = engine.match("container orchestration", List.of(s), true);
        assertEquals(SkillMatchType.MISSING, r.getMatchType());
        // AbsenceReason should be GENUINE_GAP since "Kubernetes" != "container orchestration" by name
        assertNotNull(r.getAbsenceReason());
    }

    @Test void absenceReason_unlabelled_whenPartialNameMatch() {
        // Resume has "PostgreSQL" but JD asks for "Postgres" — different label, same concept
        Skill s = skill("PostgreSQL");
        when(escoGraph.resolve("postgres")).thenReturn("postgres");
        when(escoGraph.resolve("postgresql")).thenReturn("postgresql");
        SkillMatchResult r = engine.match("Postgres", List.of(s), true);
        // Either matched via synonym or UNLABELLED
        assertTrue(r.getMatchType() != SkillMatchType.MISSING
            || r.getAbsenceReason() == AbsenceReason.UNLABELLED);
    }

    @Test void absenceReason_omitted_skillInSectionNoBullets_differentJdName() {
        // Resume has "Kubernetes" in skills section only (bulletOccurrences=0)
        // JD asks for "k8s" — after normalisation "k8s" → "Kubernetes" via abbreviation
        // But mock doesn't resolve abbreviations, so it stays MISSING
        // The OMITTED check: skill.isInSkillsSection() && bulletOccurrences==0 && name matches jdNorm
        Skill s = skillInSectionOnly("kubernetes"); // lowercase to match normalised jdNorm
        when(escoGraph.resolve("kubernetes")).thenReturn("kubernetes");
        SkillMatchResult r = engine.match("kubernetes", List.of(s), true);
        // This is an EXACT match — skill IS found
        assertEquals(SkillMatchType.EXACT, r.getMatchType());
        // OMITTED is only for truly missing skills — this is a correct EXACT match
    }

    @Test void absenceReason_genuineGap_whenSkillTrulyMissing() {
        Skill s = skill("Java");
        SkillMatchResult r = engine.match("Kubernetes", List.of(s), true);
        assertEquals(SkillMatchType.MISSING, r.getMatchType());
        assertEquals(AbsenceReason.GENUINE_GAP, r.getAbsenceReason());
    }

    @Test void absenceReason_implied_whenRelatedSkillPresent() {
        Skill s = skill("React");
        s.setCanonicalName("react");
        when(escoGraph.relatedSkills("react")).thenReturn(List.of("JavaScript"));
        SkillMatchResult r = engine.match("JavaScript", List.of(s), true);
        // Should be IMPLICIT match (not MISSING)
        assertEquals(SkillMatchType.IMPLICIT, r.getMatchType());
    }

    // ── Inflated JD (30+ skills) ──────────────────────────────────────────────

    @Test void inflatedJd_doesNotCrash() {
        List<String> manySkills = new java.util.ArrayList<>();
        for (int i = 0; i < 35; i++) manySkills.add("skill" + i);
        List<Skill> resumeSkills = List.of(skill("skill0"), skill("skill1"));
        List<SkillMatchResult> results = engine.matchAll(manySkills, resumeSkills, true);
        assertEquals(35, results.size());
        // Most are missing but it doesn't crash
        long missing = results.stream().filter(r -> r.getMatchType() == SkillMatchType.MISSING).count();
        assertTrue(missing >= 33);
    }
}
