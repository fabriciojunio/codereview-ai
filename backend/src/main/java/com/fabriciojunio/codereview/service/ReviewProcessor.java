package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.LlmAnalysisResult;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.exception.OllamaUnavailableException;
import com.fabriciojunio.codereview.model.Review;
import com.fabriciojunio.codereview.model.ReviewResult;
import com.fabriciojunio.codereview.repository.ReviewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewProcessor {

    private final ReviewRepository reviewRepository;
    private final OllamaService ollamaService;
    private final PromptBuilder promptBuilder;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Transactional
    public void process(UUID reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Review not found: " + reviewId));

        review.setStatus(Review.ReviewStatus.PROCESSING);
        reviewRepository.save(review);

        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            Optional<ReviewResponse> cached = cacheService.getCachedResult(
                    review.getSourceCode(), review.getLanguage().name());

            LlmAnalysisResult analysis;
            boolean fromCache = cached.isPresent();
            if (fromCache) {
                log.info("Cache hit for review {}, skipping LLM call", reviewId);
                analysis = convertCachedToAnalysis(cached.get());
            } else {
                String prompt = promptBuilder.buildPrompt(review.getSourceCode(), review.getLanguage());
                analysis = ollamaService.analyze(prompt);
            }

            ReviewResult result = buildResult(review, analysis);
            review.setResult(result);
            review.setStatus(Review.ReviewStatus.COMPLETED);
            review.setCompletedAt(Instant.now());
            Review saved = reviewRepository.save(review);

            // Cache only results obtained from the LLM (not those already from cache)
            if (!fromCache) {
                ReviewResponse responseToCache = buildResponseFromSaved(saved, analysis);
                cacheService.cacheResult(review.getSourceCode(), review.getLanguage().name(), responseToCache);
            }

            meterRegistry.counter("codereview.reviews.completed",
                    "language", review.getLanguage().name()).increment();

            log.info("Review {} completed with score {}", reviewId, analysis.score());
        } catch (OllamaUnavailableException e) {
            log.error("Ollama unavailable for review {}: {}", reviewId, e.getMessage());
            markFailed(review, "LLM service unavailable: " + e.getMessage());
            meterRegistry.counter("codereview.reviews.failed", "reason", "ollama_unavailable").increment();
        } catch (Exception e) {
            log.error("Unexpected error processing review {}", reviewId, e);
            markFailed(review, "Internal processing error");
            meterRegistry.counter("codereview.reviews.failed", "reason", "unexpected_error").increment();
        } finally {
            sample.stop(meterRegistry.timer("codereview.processing.time",
                    "language", review.getLanguage().name()));
        }
    }

    private ReviewResult buildResult(Review review, LlmAnalysisResult analysis) {
        try {
            return ReviewResult.builder()
                    .review(review)
                    .score(analysis.score())
                    .summary(analysis.summary())
                    .bugsJson(objectMapper.writeValueAsString(analysis.bugs()))
                    .codeSmellsJson(objectMapper.writeValueAsString(analysis.codeSmells()))
                    .solidViolationsJson(objectMapper.writeValueAsString(analysis.solidViolations()))
                    .refactoringSuggestionsJson(objectMapper.writeValueAsString(analysis.refactoringSuggestions()))
                    .positiveAspectsJson(objectMapper.writeValueAsString(analysis.positiveAspects()))
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize LLM result", e);
        }
    }

    private ReviewResponse buildResponseFromSaved(Review saved, LlmAnalysisResult analysis) {
        return new ReviewResponse(
                saved.getId(),
                saved.getStatus(),
                saved.getLanguage(),
                analysis.score(),
                analysis.summary(),
                analysis.bugs(),
                analysis.codeSmells(),
                analysis.solidViolations(),
                analysis.refactoringSuggestions(),
                analysis.positiveAspects(),
                saved.getSubmittedAt(),
                saved.getResult() != null ? saved.getResult().getAnalyzedAt() : Instant.now(),
                null
        );
    }

    private LlmAnalysisResult convertCachedToAnalysis(ReviewResponse cached) {
        return new LlmAnalysisResult(
                cached.score(),
                cached.summary(),
                cached.bugs(),
                cached.codeSmells(),
                cached.solidViolations(),
                cached.refactoringSuggestions(),
                cached.positiveAspects()
        );
    }

    private void markFailed(Review review, String errorMessage) {
        review.setStatus(Review.ReviewStatus.FAILED);
        review.setErrorMessage(errorMessage);
        review.setCompletedAt(Instant.now());
        reviewRepository.save(review);
    }
}
