package com.fabriciojunio.codereview.service;

import com.fabriciojunio.codereview.dto.ReviewRequest;
import com.fabriciojunio.codereview.dto.ReviewResponse;
import com.fabriciojunio.codereview.exception.RateLimitExceededException;
import com.fabriciojunio.codereview.messaging.ReviewProducer;
import com.fabriciojunio.codereview.model.Bug;
import com.fabriciojunio.codereview.model.CodeSmell;
import com.fabriciojunio.codereview.model.Review;
import com.fabriciojunio.codereview.model.Review.Language;
import com.fabriciojunio.codereview.model.ReviewResult;
import com.fabriciojunio.codereview.model.SolidViolation;
import com.fabriciojunio.codereview.model.User;
import com.fabriciojunio.codereview.repository.ReviewRepository;
import com.fabriciojunio.codereview.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ReviewService {

    private final int rateLimit;
    private final int rateLimitWindowHours;
    private final int maxLines;

    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final ReviewProducer reviewProducer;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public ReviewService(
            ReviewRepository reviewRepository,
            UserRepository userRepository,
            ReviewProducer reviewProducer,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${review.rate-limit:20}") int rateLimit,
            @Value("${review.rate-limit-window-hours:1}") int rateLimitWindowHours,
            @Value("${review.max-lines:500}") int maxLines) {
        this.reviewRepository = reviewRepository;
        this.userRepository = userRepository;
        this.reviewProducer = reviewProducer;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.rateLimit = rateLimit;
        this.rateLimitWindowHours = rateLimitWindowHours;
        this.maxLines = maxLines;
    }

    @Transactional
    public ReviewResponse submit(ReviewRequest request, String userEmail) {
        User user = findUser(userEmail);
        checkRateLimit(user);
        validateLineCount(request.sourceCode());

        Review review = Review.builder()
                .user(user)
                .language(request.language())
                .sourceCode(request.sourceCode())
                .sourceFilename(request.filename())
                .status(Review.ReviewStatus.PENDING)
                .build();

        Review persisted = reviewRepository.save(review);
        incrementRateLimit(user);
        reviewProducer.send(persisted.getId());

        meterRegistry.counter("codereview.reviews.submitted",
                "language", request.language().name()).increment();

        log.info("Review {} submitted by {} for {}", persisted.getId(), userEmail, request.language());
        return ReviewResponse.pending(persisted.getId(), request.language(), persisted.getSubmittedAt());
    }

    @Transactional
    public ReviewResponse submitFile(String sourceCode, Language language, String filename, String userEmail) {
        ReviewRequest request = new ReviewRequest(sourceCode, language, filename);
        return submit(request, userEmail);
    }

    @Transactional
    public ReviewResponse submitGitHub(String sourceCode, Language language, String filename, String url, String userEmail) {
        User user = findUser(userEmail);
        checkRateLimit(user);
        validateLineCount(sourceCode);

        Review review = Review.builder()
                .user(user)
                .language(language)
                .sourceCode(sourceCode)
                .sourceFilename(filename)
                .sourceUrl(url)
                .status(Review.ReviewStatus.PENDING)
                .build();

        Review persisted = reviewRepository.save(review);
        incrementRateLimit(user);
        reviewProducer.send(persisted.getId());

        return ReviewResponse.pending(persisted.getId(), language, persisted.getSubmittedAt());
    }

    @Transactional(readOnly = true)
    public ReviewResponse getResult(UUID ticketId, String userEmail) {
        Review review = reviewRepository.findByIdWithResult(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Review not found: " + ticketId));

        validateOwnership(review, userEmail);
        return toResponse(review);
    }

    /**
     * Returns the original source code of a review, for use in SSE streaming.
     * Validates ownership before returning.
     */
    @Transactional(readOnly = true)
    public String getSourceCode(UUID ticketId, String userEmail) {
        Review review = reviewRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Review not found: " + ticketId));
        validateOwnership(review, userEmail);
        return review.getSourceCode();
    }

    /**
     * Returns the language of a review, for use in SSE streaming.
     * Validates ownership before returning.
     */
    @Transactional(readOnly = true)
    public Language getLanguage(UUID ticketId, String userEmail) {
        Review review = reviewRepository.findById(ticketId)
                .orElseThrow(() -> new IllegalArgumentException("Review not found: " + ticketId));
        validateOwnership(review, userEmail);
        return review.getLanguage();
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getHistory(String userEmail, Pageable pageable) {
        User user = findUser(userEmail);
        return reviewRepository.findByUserIdOrderBySubmittedAtDesc(user.getId(), pageable)
                .map(this::toResponse);
    }

    private ReviewResponse toResponse(Review review) {
        if (review.getResult() == null) {
            return new ReviewResponse(
                    review.getId(), review.getStatus(), review.getLanguage(),
                    null, null, null, null, null, null, null,
                    review.getSubmittedAt(), null, review.getErrorMessage());
        }

        ReviewResult r = review.getResult();
        return new ReviewResponse(
                review.getId(),
                review.getStatus(),
                review.getLanguage(),
                r.getScore(),
                r.getSummary(),
                parseJson(r.getBugsJson(), new TypeReference<List<Bug>>() {}),
                parseJson(r.getCodeSmellsJson(), new TypeReference<List<CodeSmell>>() {}),
                parseJson(r.getSolidViolationsJson(), new TypeReference<List<SolidViolation>>() {}),
                parseJson(r.getRefactoringSuggestionsJson(), new TypeReference<List<String>>() {}),
                parseJson(r.getPositiveAspectsJson(), new TypeReference<List<String>>() {}),
                review.getSubmittedAt(),
                r.getAnalyzedAt(),
                review.getErrorMessage()
        );
    }

    private <T> T parseJson(String json, TypeReference<T> type) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("Failed to parse JSON field: {}", e.getMessage());
            return null;
        }
    }

    private void checkRateLimit(User user) {
        Instant now = Instant.now();
        if (user.getReviewWindowStart() == null ||
                now.isAfter(user.getReviewWindowStart().plusSeconds(rateLimitWindowHours * 3600L))) {
            // Window expired, reset
            user.setReviewWindowStart(now);
            user.setReviewCountThisHour(0);
            userRepository.save(user);
        }
        if (user.getReviewCountThisHour() >= rateLimit) {
            throw new RateLimitExceededException("Rate limit exceeded: " + rateLimit + " reviews per hour");
        }
    }

    private void incrementRateLimit(User user) {
        user.setReviewCountThisHour(user.getReviewCountThisHour() + 1);
        userRepository.save(user);
    }

    private void validateLineCount(String sourceCode) {
        long lines = sourceCode.lines().count();
        if (lines > maxLines) {
            throw new IllegalArgumentException(
                    "Source code exceeds maximum of " + maxLines + " lines (got " + lines + ")");
        }
    }

    private void validateOwnership(Review review, String userEmail) {
        if (!review.getUser().getEmail().equals(userEmail)) {
            throw new IllegalArgumentException("Review not found: " + review.getId());
        }
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
