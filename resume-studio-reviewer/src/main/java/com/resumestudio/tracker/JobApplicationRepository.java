package com.resumestudio.tracker;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JobApplicationRepository extends JpaRepository<JobApplication, String> {
    List<JobApplication> findByUserIdOrderByCreatedAtDesc(String userId);
    void deleteByUserId(String userId);
}
