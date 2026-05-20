package com.fabriciojunio.codereview.messaging;

import com.fabriciojunio.codereview.service.ReviewProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewConsumerTest {

    @Mock private ReviewProcessor reviewProcessor;

    private ReviewConsumer reviewConsumer;

    @BeforeEach
    void setUp() {
        reviewConsumer = new ReviewConsumer(reviewProcessor, new SimpleMeterRegistry());
    }

    @Test
    void consume_validMessage_delegatesToProcessor() {
        UUID id = UUID.randomUUID();
        reviewConsumer.consume(id.toString());
        verify(reviewProcessor).process(id);
    }

    @Test
    void consume_processorThrows_rethrowsForRabbitMqNack() {
        // The consumer deliberately re-throws so RabbitMQ can NACK the message
        // and route it to the DLQ after max retries are exhausted.
        UUID id = UUID.randomUUID();
        RuntimeException cause = new RuntimeException("unexpected DB error");
        doThrow(cause).when(reviewProcessor).process(id);

        assertThatThrownBy(() -> reviewConsumer.consume(id.toString()))
                .isSameAs(cause);

        verify(reviewProcessor).process(id);
    }
}
