package com.fabriciojunio.codereview.messaging;

import com.fabriciojunio.codereview.observability.MessageTrace;
import com.fabriciojunio.codereview.observability.TestTracing;
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

    /**
     * A real trace helper, not a mock. {@code consuming} is what runs the work,
     * so a mock would swallow the call and every test here would pass without a
     * single review ever being processed.
     */
    private final MessageTrace messageTrace = TestTracing.withoutCollector();

    private static final String TRACEPARENT =
            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @BeforeEach
    void setUp() {
        reviewConsumer = new ReviewConsumer(reviewProcessor, messageTrace, new SimpleMeterRegistry());
    }

    @Test
    void consume_validMessage_delegatesToProcessor() {
        UUID id = UUID.randomUUID();
        reviewConsumer.consume(id.toString(), TRACEPARENT);
        verify(reviewProcessor).process(id);
    }

    @Test
    void consume_withoutTraceparent_stillProcesses() {
        // A message published before tracing shipped, or re-queued by hand from
        // the DLQ, arrives with no header. Losing the review to keep telemetry
        // tidy would be the wrong trade.
        UUID id = UUID.randomUUID();
        reviewConsumer.consume(id.toString(), null);
        verify(reviewProcessor).process(id);
    }

    @Test
    void consume_processorThrows_rethrowsForRabbitMqNack() {
        // The consumer deliberately re-throws so RabbitMQ can NACK the message
        // and route it to the DLQ after max retries are exhausted.
        UUID id = UUID.randomUUID();
        RuntimeException cause = new RuntimeException("unexpected DB error");
        doThrow(cause).when(reviewProcessor).process(id);

        assertThatThrownBy(() -> reviewConsumer.consume(id.toString(), TRACEPARENT))
                .isSameAs(cause);

        verify(reviewProcessor).process(id);
    }
}
