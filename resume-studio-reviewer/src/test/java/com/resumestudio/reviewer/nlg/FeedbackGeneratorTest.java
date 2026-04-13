package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackGeneratorTest {

    private FeedbackGenerator generator;

    @BeforeEach
    void setUp() {
        SentenceBankOntologyService ontologyBank = mock(SentenceBankOntologyService.class);
        // Return null for all ontology lookups — falls back to SentenceBank
        when(ontologyBank.observation(anyString(), anyString(), any(), anyInt())).thenReturn(null);
        when(ontologyBank.interpretation(anyString(), anyString(), any(), anyInt())).thenReturn(null);
        when(ontologyBank.action(anyString(), anyString(), any(), anyInt())).thenReturn(null);
        generator = new FeedbackGenerator(new SentenceBank(), ontologyBank);
    }

    // ── generate() returns non-null output ───────────────────────────────────

    @Test
    void generate_alwaysReturnsOutput() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        assertNotNull(out);
        assertNotNull(out.signals());
        assertNotNull(out.fixes());
        assertNotNull(out.summaryLine());
    }

    // ── Signal list ──────────────────────────────────────────────────────────

    @Test
    void signals_alwaysContainsAtLeast3Signals() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        assertTrue(out.signals().size() >= 3);
    }

    @Test
    void signals_trustIncluded_whenChronologyUnreliable() {
        ResumeSignals s = strongSignals();
        s.setHasChronologyIssues(true);
        s.setChronologyUnreliable(true);
        s.setChronologyDescriptions(List.of("Multiple roles are marked current."));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        assertTrue(out.signals().stream().anyMatch(sig -> "trust".equals(sig.getId())));
    }

    @Test
    void signals_candidateFitPass_whenTitleAndYoeMatch() {
        ResumeSignals s = strongSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        s.setYoeFit(YoeFit.IN_RANGE);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        var fitSignal = out.signals().stream()
            .filter(sig -> "candidate_fit".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.PASS, fitSignal.getStatus());
    }

    @Test
    void signals_candidateFitFail_whenTitleMissAndYoeShort() {
        ResumeSignals s = strongSignals();
        s.setTitleMatch(TitleMatch.MISS);
        s.setYoeFit(YoeFit.UNDER_RANGE_SIGNIFICANT);
        s.setCalculatedYoe(1.0);
        s.setJdYoeMin(5.0);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        var fitSignal = out.signals().stream()
            .filter(sig -> "candidate_fit".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.FAIL, fitSignal.getStatus());
    }

    @Test
    void signals_skillCoverageFail_whenMissingMustHaves() {
        ResumeSignals s = strongSignals();
        s.setHasMissingMustHaves(true);
        SkillMatchResult missing = new SkillMatchResult("Java", true);
        s.setMustHaveResults(List.of(missing));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        var skillSignal = out.signals().stream()
            .filter(sig -> "skill_coverage".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.FAIL, skillSignal.getStatus());
    }

    @Test
    void signals_skillCoverageWarn_whenBuriedMustHaves() {
        ResumeSignals s = strongSignals();
        s.setHasBuriedMustHaves(true);
        s.setHasMissingMustHaves(false);
        SkillMatchResult buried = new SkillMatchResult("Kubernetes", true);
        buried.setVisibility(SkillVisibility.BURIED);
        buried.setMatchType(SkillMatchType.EXACT);
        s.setMustHaveResults(List.of(buried));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.POSSIBLE_FIT);
        var skillSignal = out.signals().stream()
            .filter(sig -> "skill_coverage".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.WARN, skillSignal.getStatus());
    }

    @Test
    void signals_trustNotPresent_whenEverythingClean() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        assertTrue(out.signals().stream().noneMatch(sig -> "trust".equals(sig.getId())));
    }

    // ── Fix list ─────────────────────────────────────────────────────────────

    @Test
    void fixes_missingMustHaves_addsHighImpactFix() {
        ResumeSignals s = strongSignals();
        s.setHasMissingMustHaves(true);
        SkillMatchResult missing = new SkillMatchResult("Java", true);
        s.setMustHaveResults(List.of(missing));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        assertTrue(out.fixes().stream().anyMatch(f -> f.getImpact() == ImpactLevel.HIGH));
    }

    @Test
    void fixes_missingSummary_addsHighImpactFix() {
        ResumeSignals s = strongSignals();
        s.setSummaryPresent(false);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.POSSIBLE_FIT);
        assertTrue(out.fixes().stream().anyMatch(f -> f.getAction() != null && f.getAction().toLowerCase().contains("summary")));
    }

    @Test
    void fixes_unprofessionalFilename_addsLowImpactFix() {
        ResumeSignals s = strongSignals();
        s.setFilenameProfessional(false);
        s.setFilenameGeneric(true);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        assertTrue(out.fixes().stream()
            .anyMatch(f -> "filename".equals(f.getSignalId()) && f.getImpact() == ImpactLevel.LOW));
    }

    @Test
    void fixes_orderedByImpact_highFirst() {
        ResumeSignals s = strongSignals();
        s.setHasMissingMustHaves(true);
        SkillMatchResult missing = new SkillMatchResult("Java", true);
        s.setMustHaveResults(List.of(missing));
        s.setFilenameProfessional(false);
        s.setFilenameGeneric(true);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        // First fix should be HIGH impact
        assertFalse(out.fixes().isEmpty());
        assertEquals(ImpactLevel.HIGH, out.fixes().get(0).getImpact());
    }

    @Test
    void fixes_ranksReassignedAfterSort() {
        ResumeSignals s = strongSignals();
        s.setHasMissingMustHaves(true);
        SkillMatchResult missing = new SkillMatchResult("Java", true);
        s.setMustHaveResults(List.of(missing));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        // Ranks should be sequential from 1
        for (int i = 0; i < out.fixes().size(); i++) {
            assertEquals(i + 1, out.fixes().get(i).getRank());
        }
    }

    @Test
    void fixes_unreliableChronology_prefersChronologyFix_overGapFix() {
        ResumeSignals s = strongSignals();
        s.setHasChronologyIssues(true);
        s.setChronologyUnreliable(true);
        s.setChronologyDescriptions(List.of("Multiple roles are marked current."));
        s.setHasUnexplainedGap(true);
        s.setGapDescriptions(List.of("18-month gap between A and B"));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        assertTrue(out.fixes().stream().anyMatch(f -> f.getBeforeAfter() != null
            && f.getBeforeAfter().getBefore() != null
            && f.getBeforeAfter().getBefore().contains("Align work, education, and career breaks")));
        assertFalse(out.fixes().stream().anyMatch(f -> "Label your career gap".equals(f.getAction())));
    }

    // ── Summary paragraph ─────────────────────────────────────────────────────

    @Test
    void summaryParagraph_strongFit_mentionsFoundation() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        assertTrue(out.summaryLine().toLowerCase().contains("solid") ||
                   out.summaryLine().toLowerCase().contains("foundation") ||
                   out.summaryLine().toLowerCase().contains("strong"));
    }

    @Test
    void summaryParagraph_weakFit_mentionsMissingSkill() {
        ResumeSignals s = strongSignals();
        s.setHasMissingMustHaves(true);
        SkillMatchResult missing = new SkillMatchResult("Java", true);
        s.setMustHaveResults(List.of(missing));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        assertTrue(out.summaryLine().contains("Java"));
    }

    @Test
    void summaryParagraph_notBlank() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.POSSIBLE_FIT);
        assertFalse(out.summaryLine().isBlank());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResumeSignals strongSignals() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        s.setCandidateTitle("Backend Engineer");
        s.setJdTitle("Backend Engineer");
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setYoeState(YoeState.EXPLICIT);
        s.setCalculatedYoe(5.0);
        s.setJdYoeMin(3.0);
        s.setJdYoeMax(7.0);
        s.setHasMissingMustHaves(false);
        s.setHasBuriedMustHaves(false);
        s.setAllMustHavesFound(true);
        s.setAllMustHavesVisible(true);
        s.setMustHaveResults(List.of());
        s.setSkillsFormat(SkillsFormat.FLAT_ORDERED);
        s.setCurrentCompanyTier(CompanyTier.SCALE_UP);
        s.setCurrentCompanyName("TechCorp");
        s.setSummaryPresent(true);
        s.setSummaryIsGeneric(false);
        s.setSummaryMentionsYoe(true);
        s.setSummaryMentionsSkills(true);
        s.setTitleProgression(TitleProgression.GROWING);
        s.setFilenameProfessional(true);
        s.setFilenameGeneric(false);
        s.setFormatHasPhoto(false);
        s.setFormatWallOfText(false);
        s.setFormatTooManyPages(false);
        s.setHasSkillAgeMismatch(false);
        s.setHasUnexplainedGap(false);
        return s;
    }
}
