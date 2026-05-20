package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.MetricResponse;
import com.fabriciojunio.codereview.model.AnalysisMetric;
import com.fabriciojunio.codereview.model.Review;
import com.fabriciojunio.codereview.model.User;
import com.fabriciojunio.codereview.repository.MetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MetricService {

    private final MetricRepository metricRepository;

    @Transactional
    public void record(Review review, User user, String language, String model,
                       int tokensIn, int tokensOut, int durationMs, boolean cacheHit) {
        var metric = AnalysisMetric.builder()
            .review(review)
            .user(user)
            .language(language)
            .modelUsed(model)
            .tokensIn(tokensIn)
            .tokensOut(tokensOut)
            .durationMs(durationMs)
            .cacheHit(cacheHit)
            .build();
        metricRepository.save(metric);
    }

    @Transactional(readOnly = true)
    public Page<MetricResponse> findByUser(UUID userId, Pageable pageable) {
        return metricRepository.findByUserId(userId, pageable)
            .map(m -> new MetricResponse(
                m.getId(), m.getLanguage(), m.getModelUsed(),
                m.getTokensIn(), m.getTokensOut(), m.getDurationMs(),
                m.isCacheHit(), m.getCreatedAt()
            ));
    }

    @Transactional(readOnly = true)
    public double cacheHitRate() {
        long total = metricRepository.count();
        if (total == 0) return 0.0;
        return (double) metricRepository.countCacheHits() / total * 100;
    }
}
