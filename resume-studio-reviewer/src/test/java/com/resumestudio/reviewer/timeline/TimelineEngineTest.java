package com.resumestudio.reviewer.timeline;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.TimelineEvent;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimelineEngineTest {

    private TimelineEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TimelineEngine();
    }

    // ── Title event ───────────────────────────────────────────────────────────

    @Test
    void build_exactTitleMatch_keptReading() {
        ResumeSignals s = baseSignals();
        s.setTitleMatch(TitleMatch.EXACT);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        TimelineEvent titleEvent = events.get(0);
        assertEquals(TimelineEventType.TITLE, titleEvent.getType());
        assertEquals(TimelineOutcome.KEPT_READING, titleEvent.getOutcome());
    }

    @Test
    void build_missTitleMatch_hardStop() {
        ResumeSignals s = baseSignals();
        s.setTitleMatch(TitleMatch.MISS);

        List<TimelineEvent> events = engine.build(s, Verdict.WEAK_FIT);

        TimelineEvent titleEvent = events.get(0);
        assertEquals(TimelineOutcome.HARD_STOP, titleEvent.getOutcome());
    }

    @Test
    void build_adjacentTitleMatch_keptReadingWithHesitation() {
        ResumeSignals s = baseSignals();
        s.setTitleMatch(TitleMatch.ADJACENT);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        TimelineEvent titleEvent = events.get(0);
        assertEquals(TimelineOutcome.KEPT_READING_WITH_HESITATION, titleEvent.getOutcome());
    }

    @Test
    void build_relatedTitleMatch_caution() {
        ResumeSignals s = baseSignals();
        s.setTitleMatch(TitleMatch.RELATED);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        assertEquals(TimelineOutcome.CAUTION, events.get(0).getOutcome());
    }

    // ── Title HARD_STOP ends reading — no more events except verdict ──────────

    @Test
    void build_hardStopOnTitle_noEventsAfterVerdictAndTitle() {
        ResumeSignals s = baseSignals();
        s.setTitleMatch(TitleMatch.MISS);

        List<TimelineEvent> events = engine.build(s, Verdict.WEAK_FIT);

        // After HARD_STOP title, only verdict follows
        assertEquals(2, events.size());
        assertEquals(TimelineEventType.TITLE, events.get(0).getType());
        assertEquals(TimelineEventType.VERDICT, events.get(1).getType());
    }

    // ── Summary event ─────────────────────────────────────────────────────────

    @Test
    void build_summaryNotPresent_noSummaryEvent() {
        ResumeSignals s = baseSignals();
        s.setSummaryPresent(false);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        assertFalse(events.stream().anyMatch(e -> e.getType() == TimelineEventType.SUMMARY));
    }

    @Test
    void build_summaryPresent_summaryEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setSummaryPresent(true);
        s.setSummaryMentionsYoe(true);
        s.setSummaryMentionsSkills(true);
        s.setSummaryIsGeneric(false);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.SUMMARY));
    }

    @Test
    void build_genericSummary_minorFlag() {
        ResumeSignals s = baseSignals();
        s.setSummaryPresent(true);
        s.setSummaryIsGeneric(true);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        TimelineEvent summaryEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.SUMMARY)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.MINOR_FLAG, summaryEvent.getOutcome());
    }

    // ── Skills event ──────────────────────────────────────────────────────────

    @Test
    void build_missingMustHaves_hardStopOnSkills() {
        ResumeSignals s = baseSignals();
        s.setHasMissingMustHaves(true);

        List<TimelineEvent> events = engine.build(s, Verdict.WEAK_FIT);

        TimelineEvent skillsEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.SKILLS)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.HARD_STOP, skillsEvent.getOutcome());
    }

    @Test
    void build_allMustHavesVisible_positiveSkillsEvent() {
        ResumeSignals s = baseSignals();
        s.setHasMissingMustHaves(false);
        s.setAllMustHavesVisible(true);
        s.setHasBuriedMustHaves(false);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        TimelineEvent skillsEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.SKILLS)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.POSITIVE, skillsEvent.getOutcome());
    }

    // ── YOE event ─────────────────────────────────────────────────────────────

    @Test
    void build_yoeInRange_keptReading() {
        ResumeSignals s = baseSignals();
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setYoeState(YoeState.EXPLICIT);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        TimelineEvent yoeEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.YOE)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.KEPT_READING, yoeEvent.getOutcome());
    }

    @Test
    void build_yoeSignificantlyUnder_nearExit() {
        ResumeSignals s = baseSignals();
        s.setYoeFit(YoeFit.UNDER_RANGE_SIGNIFICANT);
        s.setCalculatedYoe(2.0);
        s.setJdYoeMin(5.0);

        List<TimelineEvent> events = engine.build(s, Verdict.WEAK_FIT);

        TimelineEvent yoeEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.YOE)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.NEAR_EXIT, yoeEvent.getOutcome());
    }

    // ── Company event ─────────────────────────────────────────────────────────

    @Test
    void build_faangCompany_companyEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setCurrentCompanyTier(CompanyTier.FAANG);
        s.setCurrentCompanyName("Google");

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.COMPANY));
    }

    @Test
    void build_unknownCompany_noCompanyEvent() {
        ResumeSignals s = baseSignals();
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        s.setCompanyTierImproving(false);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        assertFalse(events.stream().anyMatch(e -> e.getType() == TimelineEventType.COMPANY));
    }

    // ── Format event ──────────────────────────────────────────────────────────

    @Test
    void build_wallOfText_formatEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setFormatWallOfText(true);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.FORMAT));
    }

    @Test
    void build_noFormatIssues_noFormatEvent() {
        ResumeSignals s = baseSignals();
        s.setFormatWallOfText(false);
        s.setFormatTooManyPages(false);
        s.setFormatHasPhoto(false);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        // Location event also uses FORMAT type — filter by checking non-location format events
        long formatCount = events.stream()
            .filter(e -> e.getType() == TimelineEventType.FORMAT)
            .count();
        // No location or format issues → no FORMAT event
        assertEquals(0, formatCount);
    }

    // ── Verdict event ─────────────────────────────────────────────────────────

    @Test
    void build_strongFit_verdictIsLast() {
        ResumeSignals s = baseSignals();
        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        TimelineEvent last = events.get(events.size() - 1);
        assertEquals(TimelineEventType.VERDICT, last.getType());
        assertEquals(TimelineOutcome.POSITIVE, last.getOutcome());
    }

    @Test
    void build_weakFit_verdictHardStop() {
        ResumeSignals s = baseSignals();
        s.setTitleMatch(TitleMatch.MISS);
        List<TimelineEvent> events = engine.build(s, Verdict.WEAK_FIT);

        TimelineEvent last = events.get(events.size() - 1);
        assertEquals(TimelineEventType.VERDICT, last.getType());
        assertEquals(TimelineOutcome.HARD_STOP, last.getOutcome());
    }

    @Test
    void build_possibleFit_verdictNeutral() {
        ResumeSignals s = baseSignals();
        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        TimelineEvent last = events.get(events.size() - 1);
        assertEquals(TimelineOutcome.NEUTRAL, last.getOutcome());
    }

    // ── Timestamps ────────────────────────────────────────────────────────────

    @Test
    void build_timeLabelStartsAt0() {
        ResumeSignals s = baseSignals();
        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);
        assertEquals("0s", events.get(0).getTimeLabel());
    }

    // ── Bullet quality ────────────────────────────────────────────────────────

    @Test
    void build_strongBullets_bulletQualityEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setImpactVerbRatio(0.8);
        s.setMetricDensity(0.5);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        // Bullet quality reuses SKILLS type with different heading
        assertTrue(events.stream().anyMatch(e ->
            e.getType() == TimelineEventType.SKILLS && "POSITIVE".equals(e.getSentiment())));
    }

    @Test
    void build_noBulletData_noBulletQualityEvent() {
        ResumeSignals s = baseSignals();
        s.setImpactVerbRatio(0.0);
        s.setMetricDensity(0.0);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        // Only one SKILLS event (the required skills one)
        long skillsCount = events.stream().filter(e -> e.getType() == TimelineEventType.SKILLS).count();
        assertEquals(1, skillsCount);
    }

    // ── YOE edge cases ────────────────────────────────────────────────────────

    @Test
    void build_yoeOverRange_caution() {
        ResumeSignals s = baseSignals();
        s.setYoeFit(YoeFit.OVER_RANGE);
        s.setCalculatedYoe(12.0);
        s.setJdYoeMin(3.0);
        s.setJdYoeMax(5.0);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        TimelineEvent yoeEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.YOE)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.CAUTION, yoeEvent.getOutcome());
    }

    @Test
    void build_yoeCannotDetermine_frictionFlag() {
        ResumeSignals s = baseSignals();
        s.setYoeFit(YoeFit.CANNOT_DETERMINE);
        s.setYoeState(YoeState.VAGUE);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        TimelineEvent yoeEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.YOE)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.FRICTION_FLAG, yoeEvent.getOutcome());
    }

    @Test
    void build_yoeUnderMinor_caution() {
        ResumeSignals s = baseSignals();
        s.setYoeFit(YoeFit.UNDER_RANGE_MINOR);
        s.setCalculatedYoe(4.0);
        s.setJdYoeMin(5.0);
        s.setJdYoeMax(8.0);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        TimelineEvent yoeEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.YOE)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.CAUTION, yoeEvent.getOutcome());
    }

    @Test
    void build_openEndedJdYoe_rangeFormatted() {
        ResumeSignals s = baseSignals();
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setJdYoeMin(5.0);
        s.setJdYoeMax(null); // open-ended
        s.setCalculatedYoe(7.0);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        TimelineEvent yoeEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.YOE)
            .findFirst().orElseThrow();
        assertTrue(yoeEvent.getDetail().contains("5+"));
    }

    // ── Location event ────────────────────────────────────────────────────────

    @Test
    void build_strictLocation_locationEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setJdLocationStrict(true);
        s.setJdLocation("London");
        s.setCandidateLocation("London, UK");

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.FORMAT
            && "POSITIVE".equals(e.getSentiment())));
    }

    @Test
    void build_strictLocation_mismatch_cautionflag() {
        ResumeSignals s = baseSignals();
        s.setJdLocationStrict(true);
        s.setJdLocation("New York");
        s.setCandidateLocation("London");

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        assertTrue(events.stream().anyMatch(e ->
            e.getType() == TimelineEventType.FORMAT && e.getDetail() != null
            && e.getDetail().contains("New York")));
    }

    @Test
    void build_strictLocation_noLocationOnResume() {
        ResumeSignals s = baseSignals();
        s.setJdLocationStrict(true);
        s.setJdLocation("San Francisco");
        s.setCandidateLocation(null);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        assertTrue(events.stream().anyMatch(e ->
            e.getType() == TimelineEventType.FORMAT && e.getOutcome() == TimelineOutcome.MINOR_FLAG));
    }

    // ── Summary edge cases ────────────────────────────────────────────────────

    @Test
    void build_summaryPartialContext_neutral() {
        ResumeSignals s = baseSignals();
        s.setSummaryPresent(true);
        s.setSummaryMentionsYoe(false);   // missing YOE
        s.setSummaryMentionsSkills(true);
        s.setSummaryIsGeneric(false);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        TimelineEvent summaryEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.SUMMARY)
            .findFirst().orElseThrow();
        assertEquals(TimelineOutcome.NEUTRAL, summaryEvent.getOutcome());
    }

    // ── Format events ─────────────────────────────────────────────────────────

    @Test
    void build_tooManyPages_formatEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setFormatTooManyPages(true);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.FORMAT));
    }

    @Test
    void build_photo_formatEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setFormatHasPhoto(true);

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.FORMAT));
    }

    // ── Company edge cases ────────────────────────────────────────────────────

    @Test
    void build_tier1Company_companyEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setCurrentCompanyTier(CompanyTier.TIER_1);
        s.setCurrentCompanyName("Stripe");

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.COMPANY));
    }

    @Test
    void build_describedCompany_companyEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setCurrentCompanyTier(CompanyTier.DESCRIBED);
        s.setCurrentCompanyName("AcmeCorp");

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.COMPANY));
    }

    @Test
    void build_unknownCompanyButImproving_companyEventIncluded() {
        ResumeSignals s = baseSignals();
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        s.setCompanyTierImproving(true);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.COMPANY));
    }

    // ── Bullet quality edge cases ─────────────────────────────────────────────

    @Test
    void build_weakBullets_bulletQualityEventWithNegativeSentiment() {
        ResumeSignals s = baseSignals();
        s.setImpactVerbRatio(0.2); // below 0.3 threshold
        s.setMetricDensity(0.05);  // below 0.1 threshold

        List<TimelineEvent> events = engine.build(s, Verdict.POSSIBLE_FIT);

        assertTrue(events.stream().anyMatch(e ->
            e.getType() == TimelineEventType.SKILLS && "NEGATIVE".equals(e.getSentiment())));
    }

    @Test
    void build_mediumBullets_noBulletQualityEvent() {
        // hasBulletQualitySignal only fires for notably strong OR notably weak bullets
        // — medium bullets (0.5 ivr, 0.3 md) produce no extra SKILLS event
        ResumeSignals s = baseSignals();
        s.setImpactVerbRatio(0.5); // neither strong nor weak
        s.setMetricDensity(0.3);

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        long skillsCount = events.stream().filter(e -> e.getType() == TimelineEventType.SKILLS).count();
        assertEquals(1, skillsCount); // only the required-skills event, no bullet quality event
    }

    // ── Near-exit stops further reading ──────────────────────────────────────

    @Test
    void build_nearExitOnYoe_noCompanyEventAfter() {
        ResumeSignals s = baseSignals();
        s.setYoeFit(YoeFit.UNDER_RANGE_SIGNIFICANT);
        s.setCurrentCompanyTier(CompanyTier.FAANG);
        s.setCurrentCompanyName("Google");
        s.setCalculatedYoe(1.0);
        s.setJdYoeMin(5.0);

        List<TimelineEvent> events = engine.build(s, Verdict.WEAK_FIT);

        // After NEAR_EXIT on YOE, no more events (company, bullets, format) should appear
        int yoeIndex = -1;
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getType() == TimelineEventType.YOE) { yoeIndex = i; break; }
        }
        assertTrue(yoeIndex >= 0);
        // All events after YOE should only be VERDICT
        for (int i = yoeIndex + 1; i < events.size() - 1; i++) {
            assertNotEquals(TimelineEventType.COMPANY, events.get(i).getType());
        }
    }

    // ── Recent role count ─────────────────────────────────────────────────────

    @Test
    void build_recentRoleCountInYoeDetail() {
        ResumeSignals s = baseSignals();
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setRecentRoleCount(3); // triggers "N roles in last 3 years" note

        List<TimelineEvent> events = engine.build(s, Verdict.STRONG_FIT);

        TimelineEvent yoeEvent = events.stream()
            .filter(e -> e.getType() == TimelineEventType.YOE)
            .findFirst().orElseThrow();
        assertTrue(yoeEvent.getDetail().contains("3 roles"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResumeSignals baseSignals() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        s.setCandidateTitle("Backend Engineer");
        s.setJdTitle("Backend Engineer");
        s.setSummaryPresent(false);
        s.setHasMissingMustHaves(false);
        s.setHasBuriedMustHaves(false);
        s.setAllMustHavesVisible(true);
        s.setSkillsFormat(SkillsFormat.FLAT_ORDERED);
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setYoeState(YoeState.EXPLICIT);
        s.setCalculatedYoe(5.0);
        s.setJdYoeMin(3.0);
        s.setJdYoeMax(7.0);
        s.setCurrentCompanyTier(CompanyTier.UNKNOWN);
        s.setCompanyTierImproving(false);
        s.setFormatWallOfText(false);
        s.setFormatTooManyPages(false);
        s.setFormatHasPhoto(false);
        s.setJdLocationStrict(false);
        s.setImpactVerbRatio(0.0);
        s.setMetricDensity(0.0);
        return s;
    }
}
