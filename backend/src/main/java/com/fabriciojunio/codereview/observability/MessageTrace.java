package com.fabriciojunio.codereview.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Carries the trace across the queue.
 *
 * <p>The problem this exists to solve: the review is accepted inside an HTTP
 * request, and processed minutes later on a listener thread. Trace context
 * lives on the thread. When the request returns, it dies with it.
 *
 * <p>Without this, the dashboard shows two unrelated traces: one that ends at
 * "review enqueued" and one that starts out of nowhere at "review processing".
 * The case anyone actually wants to investigate — the user waited four minutes,
 * where did the four minutes go — is exactly the one that gets lost, because
 * the wait is split across the two halves.
 *
 * <p>Serialisation uses the W3C format, the same one that travels in an HTTP
 * header. Storing the standard instead of an invention of ours is what lets the
 * trace cross process, broker and third-party service without translation in
 * between.
 */
@Component
public class MessageTrace {

    /** Field name in W3C. Works for an HTTP header and an AMQP header alike. */
    public static final String FIELD = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public MessageTrace(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /**
     * The current context as text, or null when no trace is in flight.
     *
     * <p>Null is a normal case, not an error: a review re-queued by a scheduled
     * job has no earlier request to inherit from.
     */
    public String capture() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        propagator.inject(current.context(), fields, Map::put);
        return fields.get(FIELD);
    }

    /**
     * Opens a span that is a child of the trace that arrived with the message.
     *
     * <p>A child, and not a continuation of the same span, because enqueuing
     * and processing are separate pieces of work happening at different times:
     * the dashboard needs to show both with their own duration, hanging off the
     * same original request.
     *
     * <p>The caller closes it with {@code span.end()} in a finally. Without
     * that the span stays open forever and the trace never shows up complete.
     */
    public Span resume(String traceparent, String name) {
        Span.Builder builder = traceparent == null || traceparent.isBlank()
                ? tracer.spanBuilder()
                : propagator.extract(Map.of(FIELD, traceparent), Map::get);
        return builder.name(name).start();
    }

    /**
     * Runs the consumption of one message inside a span linked to the publisher.
     *
     * <p>The exception is re-thrown after being recorded on the span, because
     * what to do with a failed consumption belongs to the listener: here, let
     * RabbitMQ NACK the message and route it to the dead letter queue after the
     * retries run out.
     */
    public void consuming(String traceparent, String queue, Runnable work) {
        Span span = resume(traceparent, "messaging consume");
        span.tag("messaging.destination", queue);
        try (var scope = tracer.withSpan(span)) {
            work.run();
        } catch (RuntimeException error) {
            span.error(error);
            throw error;
        } finally {
            span.end();
        }
    }
}
