package com.resumestudio.reviewer.skills;

import com.resumestudio.reviewer.model.Skill;
import com.resumestudio.reviewer.model.WorkExperience;
import com.resumestudio.reviewer.model.enums.SkillVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillVisibilityAnalyserTest {

    @Mock
    private EscoSkillGraph escoGraph;

    private SkillVisibilityAnalyser analyser;

    @BeforeEach
    void setUp() {
        // Return the skill name as-is for resolve calls
        when(escoGraph.resolve(anyString())).thenAnswer(inv -> inv.getArgument(0));
        analyser = new SkillVisibilityAnalyser(escoGraph);
    }

    // ── computeVisibility ─────────────────────────────────────────────────────

    @Test
    void visibility_surface_whenInSkillsSection() {
        Skill skill = new Skill("Java");
        skill.setInSkillsSection(true);

        SkillVisibility v = analyser.computeVisibility(skill, List.of(), null);
        assertEquals(SkillVisibility.SURFACE, v);
    }

    @Test
    void visibility_surface_whenInSummary() {
        Skill skill = new Skill("Java");
        skill.setInSkillsSection(false);

        SkillVisibility v = analyser.computeVisibility(skill, List.of(), "Senior Java engineer with 5 years.");
        assertEquals(SkillVisibility.SURFACE, v);
    }

    @Test
    void visibility_mid_whenInRecentBullet() {
        Skill skill = new Skill("Docker");
        skill.setInSkillsSection(false);

        WorkExperience recent = new WorkExperience();
        recent.setBullets(List.of("Deployed services using Docker and Kubernetes."));

        SkillVisibility v = analyser.computeVisibility(skill, List.of(recent), null);
        assertEquals(SkillVisibility.MID, v);
    }

    @Test
    void visibility_buried_whenInOlderRole() {
        Skill skill = new Skill("Docker");
        skill.setInSkillsSection(false);

        WorkExperience recent = new WorkExperience();
        recent.setBullets(List.of("Worked on backend APIs."));
        WorkExperience almostRecent = new WorkExperience();
        almostRecent.setBullets(List.of("Led team projects."));
        WorkExperience old = new WorkExperience();
        old.setBullets(List.of("Used Docker for container management."));

        // experience is most-recent-first; old is index 2 → BURIED
        SkillVisibility v = analyser.computeVisibility(skill, List.of(recent, almostRecent, old), null);
        assertEquals(SkillVisibility.BURIED, v);
    }

    @Test
    void visibility_missing_whenNotFound() {
        Skill skill = new Skill("Kubernetes");
        skill.setInSkillsSection(false);

        WorkExperience e = new WorkExperience();
        e.setBullets(List.of("Worked on backend APIs in Java."));

        SkillVisibility v = analyser.computeVisibility(skill, List.of(e), null);
        assertEquals(SkillVisibility.MISSING, v);
    }

    @Test
    void visibility_missing_whenNoExperience() {
        Skill skill = new Skill("Kubernetes");
        skill.setInSkillsSection(false);

        SkillVisibility v = analyser.computeVisibility(skill, List.of(), null);
        assertEquals(SkillVisibility.MISSING, v);
    }

    @Test
    void visibility_missing_whenNullBullets() {
        Skill skill = new Skill("Kubernetes");
        skill.setInSkillsSection(false);

        WorkExperience e = new WorkExperience();
        e.setBullets(null);

        SkillVisibility v = analyser.computeVisibility(skill, List.of(e), null);
        assertEquals(SkillVisibility.MISSING, v);
    }

    // ── analyse ───────────────────────────────────────────────────────────────

    @Test
    void analyse_setsVisibilityAndCanonicalOnAllSkills() {
        when(escoGraph.categoryOf(anyString())).thenReturn("Programming");
        Skill java = new Skill("Java");
        java.setInSkillsSection(true);

        analyser.analyse(List.of(java), List.of(), null);

        assertEquals(SkillVisibility.SURFACE, java.getVisibility());
        assertNotNull(java.getCanonicalName());
    }

    // ── findVisibilityForJdSkill ──────────────────────────────────────────────

    @Test
    void findVisibility_returnsSkillVisibility_whenInResumeSkills() {
        Skill java = new Skill("Java");
        java.setVisibility(SkillVisibility.SURFACE);

        SkillVisibility v = analyser.findVisibilityForJdSkill("Java", List.of(java), List.of(), null);
        assertEquals(SkillVisibility.SURFACE, v);
    }

    @Test
    void findVisibility_surface_whenInSummaryButNotSkills() {
        SkillVisibility v = analyser.findVisibilityForJdSkill("Java", List.of(),
            List.of(), "Experienced Java backend developer.");
        assertEquals(SkillVisibility.SURFACE, v);
    }

    @Test
    void findVisibility_mid_whenInRecentBulletAndNotSkills() {
        WorkExperience recent = new WorkExperience();
        recent.setBullets(List.of("Built APIs with Java and Spring Boot."));

        SkillVisibility v = analyser.findVisibilityForJdSkill("Java", List.of(),
            List.of(recent), null);
        assertEquals(SkillVisibility.MID, v);
    }

    @Test
    void findVisibility_missing_whenNotFound() {
        WorkExperience e = new WorkExperience();
        e.setBullets(List.of("Built APIs with Python."));

        SkillVisibility v = analyser.findVisibilityForJdSkill("Java", List.of(), List.of(e), null);
        assertEquals(SkillVisibility.MISSING, v);
    }
}
