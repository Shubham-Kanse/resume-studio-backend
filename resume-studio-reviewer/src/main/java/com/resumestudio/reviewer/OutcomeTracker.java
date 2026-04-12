package com.resumestudio.reviewer;

import com.resumestudio.reviewer.model.FeedbackReport;
import com.resumestudio.reviewer.model.ResumeSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Layer 10 — Outcome tracker (async, non-blocking, fire-and-forget).
 * Persists analysis snapshots to Supabase PostgreSQL.
 */
@Component
public class OutcomeTracker {

    private static final Logger log = LoggerFactory.getLogger(OutcomeTracker.class);

    private final AnalysisSnapshotRepository repository;

    public OutcomeTracker(AnalysisSnapshotRepository repository) {
        this.repository = repository;
    }

    @Async
    public void track(FeedbackReport report, ResumeSignals signals) {
        try {
            repository.save(new AnalysisSnapshotEntity(
                UUID.randomUUID().toString(),
                report.getVerdict() != null ? report.getVerdict().name() : "UNKNOWN",
                report.getConfidence() != null ? report.getConfidence().name() : "UNKNOWN",
                signals.getMustHaveResults() != null ? signals.getMustHaveResults().size() : 0,
                signals.isHasMissingMustHaves(),
                signals.getCalculatedYoe(),
                Instant.now()
            ));
            log.debug("OutcomeTracker: persisted to Supabase");
        } catch (Exception e) {
            log.warn("OutcomeTracker: failed to persist ({})", e.getMessage());
        }
    }
}
