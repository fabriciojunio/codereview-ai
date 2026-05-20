package com.fabriciojunio.codereview.messaging;

import com.fabriciojunio.codereview.config.RabbitConfig;
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

    public void send(UUID reviewId) {
        log.info("Enqueuing review: {}", reviewId);
        rabbitTemplate.convertAndSend(
                RabbitConfig.REVIEW_EXCHANGE,
                RabbitConfig.REVIEW_ROUTING_KEY,
                reviewId.toString()
        );
    }
}
