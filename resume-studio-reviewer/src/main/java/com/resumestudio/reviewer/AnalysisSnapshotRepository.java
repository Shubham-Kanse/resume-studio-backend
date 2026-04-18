package com.resumestudio.reviewer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface AnalysisSnapshotRepository extends JpaRepository<AnalysisSnapshotEntity, String> {

    Optional<AnalysisSnapshotEntity> findByJdHashAndResumeHash(String jdHash, String resumeHash);

    @Transactional
    @Modifying
    @Query("DELETE FROM AnalysisSnapshotEntity s WHERE s.trackedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
