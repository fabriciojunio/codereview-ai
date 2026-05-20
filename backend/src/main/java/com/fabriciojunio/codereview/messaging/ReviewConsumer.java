package com.fabriciojunio.codereview.messaging;

import com.fabriciojunio.codereview.config.RabbitConfig;
import com.fabriciojunio.codereview.service.ReviewProcessor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class ReviewConsumer {

    private final ReviewProcessor reviewProcessor;
    private final AtomicInteger activeProcessing = new AtomicInteger(0);

    public ReviewConsumer(ReviewProcessor reviewProcessor, MeterRegistry meterRegistry) {
        this.reviewProcessor = reviewProcessor;
        Gauge.builder("codereview.queue.active_processing", activeProcessing, AtomicInteger::get)
                .description("Number of reviews currently being processed")
                .register(meterRegistry);
    }

    @RabbitListener(queues = RabbitConfig.REVIEW_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void consume(String reviewIdStr) {
        UUID reviewId = UUID.fromString(reviewIdStr);
        log.info("Received review job: {}", reviewId);
        activeProcessing.incrementAndGet();
        try {
            reviewProcessor.process(reviewId);
        } catch (Exception e) {
            // Re-throw so RabbitMQ NACKs the message and routes to DLQ after max retries
            log.error("Failed to process review {}, routing to DLQ: {}", reviewId, e.getMessage());
            throw e;
        } finally {
            activeProcessing.decrementAndGet();
        }
    }
}
