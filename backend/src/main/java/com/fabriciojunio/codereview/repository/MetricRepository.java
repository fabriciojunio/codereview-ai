package com.fabriciojunio.codereview.repository;

import com.fabriciojunio.codereview.model.AnalysisMetric;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MetricRepository extends JpaRepository<AnalysisMetric, UUID> {

    Page<AnalysisMetric> findByUserId(UUID userId, Pageable pageable);

    @Query("SELECT AVG(m.durationMs) FROM AnalysisMetric m WHERE m.language = :language")
    Double avgDurationByLanguage(String language);

    @Query("SELECT COUNT(m) FROM AnalysisMetric m WHERE m.cacheHit = true")
    long countCacheHits();
}
