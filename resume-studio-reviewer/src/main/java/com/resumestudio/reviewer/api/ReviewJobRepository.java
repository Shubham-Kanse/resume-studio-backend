package com.resumestudio.reviewer.api;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Repository
public interface ReviewJobRepository extends JpaRepository<ReviewJobEntity, String> {

    List<ReviewJobEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    @Transactional
    @Modifying
    @Query("DELETE FROM ReviewJobEntity j WHERE j.status <> 'PROCESSING' AND j.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
