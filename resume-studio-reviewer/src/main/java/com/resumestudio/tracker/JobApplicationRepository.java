package com.resumestudio.tracker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface JobApplicationRepository extends JpaRepository<JobApplication, String> {
    List<JobApplication> findByUserIdOrderByCreatedAtDesc(String userId);
    void deleteByUserId(String userId);
    long countByUserId(String userId);

    @Query("""
        select j from JobApplication j
        where coalesce(j.reminderEnabled, true) = true
          and j.nextReminderAt is not null
          and j.nextReminderAt <= :now
          and j.stage not in ('Offer', 'Rejected', 'Ghosted')
    """)
    List<JobApplication> findDueReminders(@Param("now") Instant now);
}
