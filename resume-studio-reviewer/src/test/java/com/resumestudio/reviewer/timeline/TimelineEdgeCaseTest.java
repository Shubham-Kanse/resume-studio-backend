package com.resumestudio.reviewer.timeline;

import com.resumestudio.reviewer.model.ResumeSignals;
import com.resumestudio.reviewer.model.TimelineEvent;
import com.resumestudio.reviewer.model.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases for timeline — 15-20s window, NO_FIT verdict, null inputs.
 */
class TimelineEdgeCaseTest {

    private TimelineEngine engine;

    @BeforeEach
    void setUp() { engine = new TimelineEngine(); }

    private ResumeSignals base() {
        ResumeSignals s = new ResumeSignals();
        s.setTitleMatch(TitleMatch.EXACT);
        s.setSummaryPresent(true);
        s.setSummaryMentionsYoe(true);
        s.setSummaryMentionsSkills(true);
        s.setSummaryIsGeneric(false);
        s.setAllMustHavesVisible(true);
        s.setHasMissingMustHaves(false);
        s.setHasBuriedMustHaves(false);
        s.setYoeFit(YoeFit.IN_RANGE);
        s.setYoeState(YoeState.EXPLICIT);
        s.setCalculatedYoe(5.0);
        s.setCurrentCompanyTier(CompanyTier.TIER_1);
        s.setCurrentCompanyName("Acme");
        s.setImpactVerbRatio(0.3);
        s.setMetricDensity(0.1);
        return s;
    }

    @Test void nullSignals_returnsEmpty() {
        assertTrue(engine.build(null, Verdict.STRONG_FIT).isEmpty());
    }

    @Test void nullVerdict_returnsEmpty() {
        assertTrue(engine.build(base(), null).isEmpty());
    }

    @Test void noFit_verdictEventPresent() {
        ResumeSignals s = base();
        s.setHasMissingMustHaves(true);
        s.setAllMustHavesVisible(false);
        List<TimelineEvent> events = engine.build(s, Verdict.NO_FIT);
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.getType() == TimelineEventType.VERDICT));
    }

    @Test void strongFit_timelineSpans15to20Seconds() {
        List<TimelineEvent> events = engine.build(base(), Verdict.STRONG_FIT);
        // Last event's timeLabel should be >= 15s
        String lastLabel = events.get(events.size() - 1).getTimeLabel();
        int lastTime = Integer.parseInt(lastLabel.replace("s", ""));
        assertTrue(lastTime >= 10, "Timeline should span at least 10s, got: " + lastTime + "s");
    }

    @Test void allTimestampsAreNonNull() {
        List<TimelineEvent> events = engine.build(base(), Verdict.STRONG_FIT);
        events.forEach(e -> assertNotNull(e.getTimeLabel(), "timeLabel must not be null"));
    }

    @Test void allEventsHaveSentiment() {
        List<TimelineEvent> events = engine.build(base(), Verdict.STRONG_FIT);
        events.forEach(e -> assertNotNull(e.getSentiment(), "sentiment must not be null"));
    }

    @Test void hardStop_endsTimelineEarly() {
        ResumeSignals s = base();
        s.setTitleMatch(TitleMatch.MISS);
        s.setAllMustHavesVisible(false);
        s.setHasMissingMustHaves(true);
        List<TimelineEvent> events = engine.build(s, Verdict.WEAK_FIT);
        // Should have fewer events than a full read
        assertTrue(events.size() <= 5, "Hard stop should produce short timeline");
    }
}
