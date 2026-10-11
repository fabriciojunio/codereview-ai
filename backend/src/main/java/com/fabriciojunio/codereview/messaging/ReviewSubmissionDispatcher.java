package com.fabriciojunio.codereview.messaging;

import com.fabriciojunio.codereview.model.Review;
import com.fabriciojunio.codereview.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewSubmissionDispatcher {
    private final ReviewProducer producer;
    private final ReviewRepository reviews;

    // A consumer on another connection must see the ticket before receiving it.
    // A rollback never sends the event. This is not a durable outbox: a process
    // crash between commit and delivery still needs operational recovery.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(ReviewSubmitted event) {
        try {
            producer.send(event.reviewId());
        } catch (RuntimeException failure) {
            log.error("Queue delivery failed for review {}", event.reviewId(), failure);
            reviews.findById(event.reviewId()).ifPresent(review -> {
                review.setStatus(Review.ReviewStatus.FAILED);
                review.setCompletedAt(Instant.now());
                review.setErrorMessage("Queue delivery failed. Please submit the review again.");
                reviews.save(review);
            });
        }
    }
}
