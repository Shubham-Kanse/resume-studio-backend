package com.resumestudio.reviewer.nlg;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.SkillMatchResult;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FeedbackGeneratorTest {

    private FeedbackGenerator generator;

    @BeforeEach
    void setUp() {
        // SentenceBank has no dependencies — use directly
        generator = new FeedbackGenerator(new SentenceBank());
    }

    // ── generate() returns non-null output ───────────────────────────────────

    @Test
    void generate_alwaysReturnsOutput() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        assertNotNull(out);
        assertNotNull(out.signals());
        assertNotNull(out.fixes());
        assertNotNull(out.summaryParagraph());
    }

    // ── Signal list ──────────────────────────────────────────────────────────

    @Test
    void signals_alwaysContains6Signals() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        assertEquals(6, out.signals().size());
    }

    @Test
    void signals_titlePass_whenExactMatch() {
        ResumeSignals s = strongSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        var titleSignal = out.signals().stream()
            .filter(sig -> "title_match".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.PASS, titleSignal.getStatus());
    }

    @Test
    void signals_titleFail_whenMiss() {
        ResumeSignals s = strongSignals();
        s.setTitleMatch(TitleMatch.MISS);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        var titleSignal = out.signals().stream()
            .filter(sig -> "title_match".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.FAIL, titleSignal.getStatus());
    }

    @Test
    void signals_yoePass_whenInRange() {
        ResumeSignals s = strongSignals();
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setYoeState(YoeState.EXPLICIT);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        var yoeSignal = out.signals().stream()
            .filter(sig -> "yoe_fit".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.PASS, yoeSignal.getStatus());
    }

    @Test
    void signals_yoeFail_whenCannotDetermine() {
        ResumeSignals s = strongSignals();
        s.setYoeFit(YoeFit.CANNOT_DETERMINE);
        s.setYoeState(YoeState.MISSING);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        var yoeSignal = out.signals().stream()
            .filter(sig -> "yoe_fit".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.FAIL, yoeSignal.getStatus());
    }

    @Test
    void signals_skillsFail_whenMissingMustHaves() {
        ResumeSignals s = strongSignals();
        s.setHasMissingMustHaves(true);
        SkillMatchResult missing = new SkillMatchResult("Java", true);
        s.setMustHaveResults(List.of(missing));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        var skillSignal = out.signals().stream()
            .filter(sig -> "must_haves_visible".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.FAIL, skillSignal.getStatus());
    }

    @Test
    void signals_skillsWarn_whenBuriedMustHaves() {
        ResumeSignals s = strongSignals();
        s.setHasBuriedMustHaves(true);
        s.setHasMissingMustHaves(false);
        SkillMatchResult buried = new SkillMatchResult("Kubernetes", true);
        buried.setVisibility(SkillVisibility.BURIED);
        buried.setMatchType(SkillMatchType.EXACT);
        s.setMustHaveResults(List.of(buried));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.POSSIBLE_FIT);
        var skillSignal = out.signals().stream()
            .filter(sig -> "must_haves_visible".equals(sig.getId())).findFirst().orElseThrow();
        assertEquals(SignalStatus.WARN, skillSignal.getStatus());
    }

    // ── Fix list ─────────────────────────────────────────────────────────────

    @Test
    void fixes_missingMustHaves_addsHighImpactFix() {
        ResumeSignals s = strongSignals();
        s.setHasMissingMustHaves(true);
        SkillMatchResult missing = new SkillMatchResult("Java", true);
        s.setMustHaveResults(List.of(missing));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        assertTrue(out.fixes().stream()
            .anyMatch(f -> "must_haves_visible".equals(f.getSignalId()) && f.getImpact() == ImpactLevel.HIGH));
    }

    @Test
    void fixes_missingSummary_addsHighImpactFix() {
        ResumeSignals s = strongSignals();
        s.setSummaryPresent(false);
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.POSSIBLE_FIT);
        assertTrue(out.fixes().stream()
            .anyMatch(f -> "summary_quality".equals(f.getSignalId())));
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

    // ── Summary paragraph ─────────────────────────────────────────────────────

    @Test
    void summaryParagraph_strongFit_mentionsFoundation() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.STRONG_FIT);
        assertTrue(out.summaryParagraph().toLowerCase().contains("solid") ||
                   out.summaryParagraph().toLowerCase().contains("foundation") ||
                   out.summaryParagraph().toLowerCase().contains("strong"));
    }

    @Test
    void summaryParagraph_weakFit_mentionsMissingSkill() {
        ResumeSignals s = strongSignals();
        s.setHasMissingMustHaves(true);
        SkillMatchResult missing = new SkillMatchResult("Java", true);
        s.setMustHaveResults(List.of(missing));
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.WEAK_FIT);
        assertTrue(out.summaryParagraph().contains("Java"));
    }

    @Test
    void summaryParagraph_notBlank() {
        ResumeSignals s = strongSignals();
        FeedbackGenerator.FeedbackOutput out = generator.generate(s, Verdict.POSSIBLE_FIT);
        assertFalse(out.summaryParagraph().isBlank());
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
