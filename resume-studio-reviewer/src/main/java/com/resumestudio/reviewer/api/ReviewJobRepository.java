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

    @org.springframework.data.jpa.repository.Query(
        "SELECT j FROM ReviewJobEntity j WHERE j.userId = :userId ORDER BY j.createdAt DESC")
    org.springframework.data.domain.Page<ReviewJobEntity> findByUserIdPaged(
        @Param("userId") String userId,
        org.springframework.data.domain.Pageable pageable);

    @Transactional
    @Modifying
    @Query("DELETE FROM ReviewJobEntity j WHERE j.status <> 'PROCESSING' AND j.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
