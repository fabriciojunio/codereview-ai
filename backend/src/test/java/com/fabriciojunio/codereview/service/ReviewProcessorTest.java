package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.LlmAnalysisResult;
import com.fabriciojunio.codereview.exception.OllamaUnavailableException;
import com.fabriciojunio.codereview.model.Review;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fabriciojunio.codereview.model.User;
import com.fabriciojunio.codereview.repository.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewProcessorTest {

    @Mock private ReviewRepository reviewRepository;
    @Mock private OllamaService ollamaService;
    @Mock private PromptBuilder promptBuilder;
    @Mock private CacheService cacheService;

    private ReviewProcessor reviewProcessor;
    private Review testReview;

    @BeforeEach
    void setUp() {
        reviewProcessor = new ReviewProcessor(
                reviewRepository, ollamaService, promptBuilder,
                cacheService, new ObjectMapper(), new SimpleMeterRegistry());

        testReview = Review.builder()
                .id(UUID.randomUUID())
                .user(User.builder().id(UUID.randomUUID()).email("test@test.com").build())
                .language(Language.java)
                .sourceCode("public class Hello {}")
                .status(Review.ReviewStatus.PENDING)
                .build();

        when(reviewRepository.findById(testReview.getId())).thenReturn(Optional.of(testReview));
        when(reviewRepository.save(any())).thenReturn(testReview);
        when(cacheService.getCachedResult(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void process_successfulAnalysis_setsCompleted() {
        LlmAnalysisResult analysis = new LlmAnalysisResult(
                85, "Good code", List.of(), List.of(), List.of(),
                List.of("Extract method"), List.of("Good naming"));

        when(promptBuilder.buildPrompt(anyString(), any())).thenReturn("prompt");
        when(ollamaService.analyze(anyString())).thenReturn(analysis);

        reviewProcessor.process(testReview.getId());

        assertThat(testReview.getStatus()).isEqualTo(Review.ReviewStatus.COMPLETED);
        assertThat(testReview.getResult()).isNotNull();
        assertThat(testReview.getResult().getScore()).isEqualTo(85);
    }

    @Test
    void process_ollamaUnavailable_setsFailedStatus() {
        when(promptBuilder.buildPrompt(anyString(), any())).thenReturn("prompt");
        when(ollamaService.analyze(anyString())).thenThrow(new OllamaUnavailableException("Ollama down"));

        reviewProcessor.process(testReview.getId());

        assertThat(testReview.getStatus()).isEqualTo(Review.ReviewStatus.FAILED);
        assertThat(testReview.getErrorMessage()).contains("LLM service unavailable");
    }

    @Test
    void process_cacheHit_skipsOllamaCall() {
        com.fabriciojunio.codereview.dto.ReviewResponse cached = ReviewResponseFixture.completed();
        when(cacheService.getCachedResult(anyString(), anyString())).thenReturn(Optional.of(cached));

        reviewProcessor.process(testReview.getId());

        verify(ollamaService, never()).analyze(anyString());
        assertThat(testReview.getStatus()).isEqualTo(Review.ReviewStatus.COMPLETED);
    }

    // Simple fixture helper
    static class ReviewResponseFixture {
        static com.fabriciojunio.codereview.dto.ReviewResponse completed() {
            return new com.fabriciojunio.codereview.dto.ReviewResponse(
                    UUID.randomUUID(),
                    Review.ReviewStatus.COMPLETED,
                    Language.java,
                    80, "Good code",
                    List.of(), List.of(), List.of(),
                    List.of(), List.of(),
                    java.time.Instant.now(), java.time.Instant.now(), null
            );
        }
    }
}
