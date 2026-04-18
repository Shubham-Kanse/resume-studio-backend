package com.resumestudio.reviewer.api;

import com.resumestudio.reviewer.AnalysisSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Purges stale rows daily to keep tables lean.
 * - review_jobs: completed jobs older than 30 days
 * - analysis_snapshots: rows older than 90 days
 */
@Component
public class ReviewJobCleanup {

    private static final Logger log = LoggerFactory.getLogger(ReviewJobCleanup.class);

    private final ReviewJobRepository jobRepo;
    private final AnalysisSnapshotRepository snapshotRepo;

    public ReviewJobCleanup(ReviewJobRepository jobRepo, AnalysisSnapshotRepository snapshotRepo) {
        this.jobRepo = jobRepo;
        this.snapshotRepo = snapshotRepo;
    }

    @Scheduled(cron = "0 0 3 * * *") // 3am daily
    @Transactional
    public void purge() {
        int jobs = jobRepo.deleteOlderThan(Instant.now().minus(30, ChronoUnit.DAYS));
        if (jobs > 0) log.info("Purged {} stale review_jobs older than 30 days", jobs);

        int snapshots = snapshotRepo.deleteOlderThan(Instant.now().minus(90, ChronoUnit.DAYS));
        if (snapshots > 0) log.info("Purged {} analysis_snapshots older than 90 days", snapshots);
    }
}
