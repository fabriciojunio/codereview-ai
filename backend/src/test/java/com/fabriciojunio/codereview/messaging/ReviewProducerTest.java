package com.fabriciojunio.codereview.messaging;

import com.fabriciojunio.codereview.config.RabbitConfig;
import com.fabriciojunio.codereview.observability.MessageTrace;
import com.fabriciojunio.codereview.observability.TestTracing;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * What the producer puts on the message besides the id.
 *
 * <p>The review id was never the interesting part. What matters here is that
 * the trace of the request that accepted the review travels with it, because
 * the queue wait is usually the largest slice of the user's wait and it is
 * exactly the slice that falls into the gap when the two halves are separate
 * traces.
 */
@DisplayName("Review producer")
class ReviewProducerTest {

    private final TestTracing.Setup setup = TestTracing.build();
    private final MessageTrace messageTrace = setup.trace();
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final ReviewProducer producer = new ReviewProducer(rabbitTemplate, messageTrace);

    @Test
    @DisplayName("publishes to the review exchange with the id as the body")
    void publishesTheId() {
        UUID id = UUID.randomUUID();

        producer.send(id);

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.REVIEW_EXCHANGE),
                eq(RabbitConfig.REVIEW_ROUTING_KEY),
                eq((Object) id.toString()),
                any(MessagePostProcessor.class));
    }

    @Test
    @DisplayName("the trace of the accepting request rides on the message header")
    void carriesTheTraceparent() throws Exception {
        Span ofRequest = messageTrace.resume(null, "request");
        String traceId = ofRequest.context().traceId();

        Message message;
        var context = new OtelCurrentTraceContext();
        try (var ignored = context.maybeScope(ofRequest.context())) {
            message = applyPostProcessor(UUID.randomUUID());
        }
        ofRequest.end();

        String header = message.getMessageProperties().getHeader(MessageTrace.FIELD);
        assertThat(header)
                .as("without this header the queue wait falls between two unrelated traces")
                .contains(traceId);
    }

    @Test
    @DisplayName("with no trace in flight it sends no header, instead of one saying null")
    void omitsTheHeaderWhenThereIsNoTrace() throws Exception {
        // The case of a review re-queued by a scheduled job. A header carrying
        // the string "null" would be worse than none: the consumer would try to
        // parse it and every such message would open a broken trace.
        Message message = applyPostProcessor(UUID.randomUUID());

        String header = message.getMessageProperties().getHeader(MessageTrace.FIELD);
        assertThat(header).isNull();
    }

    /** Sends one review and runs whatever the producer asked to be applied. */
    private Message applyPostProcessor(UUID id) throws Exception {
        RabbitTemplate template = mock(RabbitTemplate.class);
        var localProducer = new ReviewProducer(template, messageTrace);

        localProducer.send(id);

        var captor = org.mockito.ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(template).convertAndSend(
                eq(RabbitConfig.REVIEW_EXCHANGE),
                eq(RabbitConfig.REVIEW_ROUTING_KEY),
                eq((Object) id.toString()),
                captor.capture());

        Message message = new Message(id.toString().getBytes(), new MessageProperties());
        return captor.getValue().postProcessMessage(message);
    }
}
