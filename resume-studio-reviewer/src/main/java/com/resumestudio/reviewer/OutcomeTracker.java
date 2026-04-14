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
        track(report, signals, null, null, null);
    }

    @Async
    public void track(FeedbackReport report, ResumeSignals signals,
                      String userId, String resumeHash, String jdHash) {
        try {
            String roleTitle = report.getRoleContext() != null ? report.getRoleContext().getTitle() : null;
            String roleDomain = report.getRoleContext() != null ? report.getRoleContext().getDomain() : null;

            int skillMatchScore = 0;
            if (signals.getMustHaveResults() != null && !signals.getMustHaveResults().isEmpty()) {
                long total = signals.getMustHaveResults().size();
                long found = signals.getMustHaveResults().stream()
                    .filter(r -> r.getVisibility() != com.resumestudio.reviewer.model.enums.SkillVisibility.MISSING)
                    .count();
                skillMatchScore = (int) (found * 100 / total);
            }

            repository.save(new AnalysisSnapshotEntity(
                UUID.randomUUID().toString(),
                report.getVerdict() != null ? report.getVerdict().name() : "UNKNOWN",
                report.getConfidence() != null ? report.getConfidence().name() : "UNKNOWN",
                signals.getMustHaveResults() != null ? signals.getMustHaveResults().size() : 0,
                signals.isHasMissingMustHaves(),
                signals.getCalculatedYoe(),
                Instant.now(),
                userId,
                roleTitle,
                roleDomain,
                null, // compositeScore — requires ResumeScoreCalculator, wired separately if needed
                skillMatchScore,
                jdHash,
                resumeHash
            ));
            log.debug("OutcomeTracker: persisted snapshot");
        } catch (Exception e) {
            log.warn("OutcomeTracker: failed to persist ({})", e.getMessage());
        }
    }
}
