package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.ReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.exception.RateLimitExceededException;
import com.fabriciojunio.codereview.exception.ReviewNotFoundException;
import com.fabriciojunio.codereview.messaging.ReviewProducer;
import com.fabriciojunio.codereview.model.Review;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fabriciojunio.codereview.model.User;
import com.fabriciojunio.codereview.repository.ReviewRepository;
import com.fabriciojunio.codereview.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private ReviewRepository reviewRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReviewProducer reviewProducer;

    private ReviewService reviewService;

    private User testUser;

    @BeforeEach
    void setUp() {
        reviewService = new ReviewService(
                reviewRepository, userRepository, reviewProducer,
                new ObjectMapper(), new SimpleMeterRegistry(), 20, 1, 500);

        testUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@example.com")
                .name("Test User")
                .password("hash")
                .reviewCountThisHour(0)
                .build();
    }

    @Test
    void submit_validRequest_returnsTicketId() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        Review savedReview = Review.builder()
                .id(UUID.randomUUID())
                .user(testUser)
                .language(Language.java)
                .sourceCode("public class A {}")
                .status(Review.ReviewStatus.PENDING)
                .submittedAt(Instant.now())
                .build();
        when(reviewRepository.save(any())).thenReturn(savedReview);
        when(userRepository.save(any())).thenReturn(testUser);

        ReviewRequest request = new ReviewRequest("public class A {}", Language.java, null);
        ReviewResponse response = reviewService.submit(request, "test@example.com");

        assertThat(response.status()).isEqualTo(Review.ReviewStatus.PENDING);
        verify(reviewProducer).send(any(UUID.class));
    }

    @Test
    void submit_exceedsLineLimit_throwsIllegalArgumentException() {
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        String manyLines = "line\n".repeat(501);
        ReviewRequest request = new ReviewRequest(manyLines, Language.java, null);

        assertThatThrownBy(() -> reviewService.submit(request, "test@example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500 lines");
    }

    @Test
    void submit_rateLimitExceeded_throwsRateLimitExceededException() {
        testUser.setReviewCountThisHour(20);
        testUser.setReviewWindowStart(Instant.now());
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        ReviewRequest request = new ReviewRequest("public class A {}", Language.java, null);

        assertThatThrownBy(() -> reviewService.submit(request, "test@example.com"))
                .isInstanceOf(RateLimitExceededException.class);
    }

    /**
     * Pedir a revisão de outra pessoa responde igual a pedir uma que não
     * existe, e a API traduz isso em 404. Antes virava 400, que é status
     * errado para recurso inexistente e atrapalha o monitoramento.
     */
    @Test
    void getResult_wrongOwner_throwsReviewNotFound() {
        User otherUser = User.builder()
                .id(UUID.randomUUID())
                .email("other@example.com")
                .build();
        Review review = Review.builder()
                .id(UUID.randomUUID())
                .user(otherUser)
                .language(Language.java)
                .status(Review.ReviewStatus.PENDING)
                .submittedAt(Instant.now())
                .build();

        when(reviewRepository.findByIdWithResult(any())).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> reviewService.getResult(review.getId(), "test@example.com"))
                .isInstanceOf(ReviewNotFoundException.class);
    }
}
