package com.fabriciojunio.codereview.messaging;

import com.fabriciojunio.codereview.config.RabbitConfig;
import com.fabriciojunio.codereview.observability.MessageTrace;
import com.fabriciojunio.codereview.service.ReviewProcessor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class ReviewConsumer {

    private final ReviewProcessor reviewProcessor;
    private final MessageTrace messageTrace;
    private final AtomicInteger activeProcessing = new AtomicInteger(0);

    public ReviewConsumer(ReviewProcessor reviewProcessor,
                          MessageTrace messageTrace,
                          MeterRegistry meterRegistry) {
        this.reviewProcessor = reviewProcessor;
        this.messageTrace = messageTrace;
        Gauge.builder("codereview.queue.active_processing", activeProcessing, AtomicInteger::get)
                .description("Number of reviews currently being processed")
                .register(meterRegistry);
    }

    /**
     * The traceparent header is optional on purpose. A message published before
     * this version shipped, or re-queued by hand from the dead letter queue,
     * arrives without it and must still be processed: losing a review to keep
     * telemetry tidy would be the wrong trade.
     */
    @RabbitListener(queues = RabbitConfig.REVIEW_QUEUE, containerFactory = "rabbitListenerContainerFactory")
    public void consume(String reviewIdStr,
                        @Header(name = MessageTrace.FIELD, required = false) String traceparent) {
        UUID reviewId = UUID.fromString(reviewIdStr);
        log.info("Received review job: {}", reviewId);
        activeProcessing.incrementAndGet();
        try {
            messageTrace.consuming(traceparent, RabbitConfig.REVIEW_QUEUE,
                    () -> reviewProcessor.process(reviewId));
        } catch (Exception e) {
            // Re-throw so RabbitMQ NACKs the message and routes to DLQ after max retries
            log.error("Failed to process review {}, routing to DLQ: {}", reviewId, e.getMessage());
            throw e;
        } finally {
            activeProcessing.decrementAndGet();
        }
    }
}
