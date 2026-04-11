package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.enums.SkillsFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillsFormatAnalyserTest {

    @Mock
    private EscoSkillGraph escoGraph;

    private SkillsFormatAnalyser analyser;

    @BeforeEach
    void setUp() {
        when(escoGraph.resolve(anyString())).thenAnswer(inv -> inv.getArgument(0));
        analyser = new SkillsFormatAnalyser(escoGraph);
    }

    // ── null / empty guards ───────────────────────────────────────────────────

    @Test
    void refine_nullMustHaves_returnsInitial() {
        assertEquals(SkillsFormat.FLAT_UNORDERED,
            analyser.refine(SkillsFormat.FLAT_UNORDERED, List.of(skill("Java")), null));
    }

    @Test
    void refine_emptyMustHaves_returnsInitial() {
        assertEquals(SkillsFormat.FLAT_UNORDERED,
            analyser.refine(SkillsFormat.FLAT_UNORDERED, List.of(skill("Java")), List.of()));
    }

    @Test
    void refine_emptySkills_returnsInitial() {
        assertEquals(SkillsFormat.FLAT_UNORDERED,
            analyser.refine(SkillsFormat.FLAT_UNORDERED, List.of(), List.of("Java")));
    }

    // ── FLAT_UNORDERED refinement ─────────────────────────────────────────────

    @Test
    void refine_flat_orderedWhenMustHaveIsFirst() {
        // Java is must-have and is first in the list
        List<Skill> skills = List.of(skill("Java"), skill("Docker"), skill("Git"));
        SkillsFormat result = analyser.refine(SkillsFormat.FLAT_UNORDERED, skills, List.of("Java"));
        assertEquals(SkillsFormat.FLAT_ORDERED, result);
    }

    @Test
    void refine_flat_orderedWhenMustHaveIsSecond() {
        List<Skill> skills = List.of(skill("Git"), skill("Java"), skill("Docker"));
        SkillsFormat result = analyser.refine(SkillsFormat.FLAT_UNORDERED, skills, List.of("Java"));
        assertEquals(SkillsFormat.FLAT_ORDERED, result);
    }

    @Test
    void refine_flat_orderedWhenMustHaveIsThird() {
        List<Skill> skills = List.of(skill("Git"), skill("Maven"), skill("Java"), skill("Docker"));
        SkillsFormat result = analyser.refine(SkillsFormat.FLAT_UNORDERED, skills, List.of("Java"));
        assertEquals(SkillsFormat.FLAT_ORDERED, result);
    }

    @Test
    void refine_flat_unorderedWhenMustHaveIsBeyondTop3() {
        List<Skill> skills = List.of(skill("Git"), skill("Maven"), skill("Docker"), skill("Java"));
        SkillsFormat result = analyser.refine(SkillsFormat.FLAT_UNORDERED, skills, List.of("Java"));
        assertEquals(SkillsFormat.FLAT_UNORDERED, result);
    }

    // ── CATEGORISED_UNORDERED refinement ─────────────────────────────────────

    @Test
    void refine_categorised_optimalWhenMustHaveFirstInCategory() {
        Skill java = skillWithCategory("Java", "Programming");
        Skill python = skillWithCategory("Python", "Programming");
        Skill docker = skillWithCategory("Docker", "DevOps");

        // Java is must-have and first in Programming category
        List<Skill> skills = List.of(java, python, docker);
        SkillsFormat result = analyser.refine(SkillsFormat.CATEGORISED_UNORDERED, skills, List.of("Java"));
        assertEquals(SkillsFormat.OPTIMAL, result);
    }

    @Test
    void refine_categorised_unorderedWhenMustHaveNotFirst() {
        // checkCount = min(2, inCategory.size()). Java must be at index >= 2 to miss the check.
        Skill python = skillWithCategory("Python", "Programming");
        Skill ruby = skillWithCategory("Ruby", "Programming");
        Skill java = skillWithCategory("Java", "Programming");  // index 2 — outside checkCount
        Skill docker = skillWithCategory("Docker", "DevOps");

        List<Skill> skills = List.of(python, ruby, java, docker);
        SkillsFormat result = analyser.refine(SkillsFormat.CATEGORISED_UNORDERED, skills, List.of("Java"));
        assertEquals(SkillsFormat.CATEGORISED_UNORDERED, result);
    }

    @Test
    void refine_categorised_unorderedWhenNoMatchingCategory() {
        Skill docker = skillWithCategory("Docker", "DevOps");
        Skill kubernetes = skillWithCategory("Kubernetes", "DevOps");

        // Java is must-have but not in skills at all
        List<Skill> skills = List.of(docker, kubernetes);
        SkillsFormat result = analyser.refine(SkillsFormat.CATEGORISED_UNORDERED, skills, List.of("Java"));
        assertEquals(SkillsFormat.CATEGORISED_UNORDERED, result);
    }

    // ── Non-upgradeable formats pass through ──────────────────────────────────

    @Test
    void refine_prose_unchanged() {
        assertEquals(SkillsFormat.PROSE,
            analyser.refine(SkillsFormat.PROSE, List.of(skill("Java")), List.of("Java")));
    }

    @Test
    void refine_noSection_unchanged() {
        assertEquals(SkillsFormat.NO_SECTION,
            analyser.refine(SkillsFormat.NO_SECTION, List.of(skill("Java")), List.of("Java")));
    }

    @Test
    void refine_bulletList_unchanged() {
        assertEquals(SkillsFormat.BULLET_LIST,
            analyser.refine(SkillsFormat.BULLET_LIST, List.of(skill("Java")), List.of("Java")));
    }

    @Test
    void refine_optimal_unchanged() {
        assertEquals(SkillsFormat.OPTIMAL,
            analyser.refine(SkillsFormat.OPTIMAL, List.of(skill("Java")), List.of("Java")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Skill skill(String name) {
        return new Skill(name);
    }

    private Skill skillWithCategory(String name, String category) {
        Skill s = new Skill(name);
        s.setCategory(category);
        return s;
    }
}
