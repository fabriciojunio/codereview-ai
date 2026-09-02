package com.fabriciojunio.codereview.messaging;

import com.fabriciojunio.codereview.config.RabbitConfig;
import com.fabriciojunio.codereview.observability.MessageTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewProducer {

    private final RabbitTemplate rabbitTemplate;
    private final MessageTrace messageTrace;

    public void send(UUID reviewId) {
        log.info("Enqueuing review: {}", reviewId);

        // The trace of the HTTP request that accepted this review travels with
        // the message. Without it the dashboard shows two unrelated traces and
        // the queue wait — usually the largest slice of the user's wait — falls
        // into the gap between them.
        String traceparent = messageTrace.capture();

        rabbitTemplate.convertAndSend(
                RabbitConfig.REVIEW_EXCHANGE,
                RabbitConfig.REVIEW_ROUTING_KEY,
                reviewId.toString(),
                message -> {
                    if (traceparent != null) {
                        message.getMessageProperties()
                                .setHeader(MessageTrace.FIELD, traceparent);
                    }
                    return message;
                }
        );
    }
}
