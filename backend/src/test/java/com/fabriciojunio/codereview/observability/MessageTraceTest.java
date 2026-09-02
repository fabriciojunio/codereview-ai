package com.fabriciojunio.codereview.observability;

import io.micrometer.tracing.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trace crossing the boundary where it normally gets lost.
 *
 * <p>The boundary is the queue. The review is accepted inside an HTTP request
 * and processed minutes later, on a listener thread, long after that request
 * finished. Trace context lives on the thread, so it dies when the response
 * goes out, and the dashboard shows two unrelated traces instead of one review.
 *
 * <p>These tests use the real OpenTelemetry rather than a double, because what
 * needs proving is the W3C format: a double would accept any string as context
 * and the tests would pass on a propagation that does not work.
 */
@DisplayName("Message trace")
class MessageTraceTest {

    private final TestTracing.Setup setup = TestTracing.build();
    private final MessageTrace trace = setup.trace();
    private final TestTracing.Memory memory = setup.memory();

    private static final String QUEUE = "review.queue";

    @Test
    @DisplayName("with no trace in flight it invents no context: returns null")
    void noTraceReturnsNull() {
        assertThat(trace.capture()).isNull();
    }

    @Test
    @DisplayName("captures in W3C format, which is what crosses HTTP and AMQP untranslated")
    void capturesInTheStandardFormat() {
        Span span = trace.resume(null, "request");
        String captured = withSpanOpen(span, trace::capture);

        // 00-<32 hex of trace>-<16 hex of span>-<2 hex of flags>
        assertThat(captured).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    @DisplayName("what travelled on the message reopens the SAME trace, not a new one")
    void processingContinuesTheRequestTrace() {
        Span ofRequest = trace.resume(null, "request");
        String onTheMessage = withSpanOpen(ofRequest, trace::capture);
        String originalTrace = ofRequest.context().traceId();
        ofRequest.end();

        // The request is over by now. It is the listener thread that carries on.
        Span ofProcessing = trace.resume(onTheMessage, "review process");
        ofProcessing.end();

        assertThat(ofProcessing.context().traceId())
                .as("processing has to land in the trace of the request that asked for the review")
                .isEqualTo(originalTrace);
    }

    @Test
    @DisplayName("consuming lands in the publisher's trace")
    void consumingContinuesThePublisherTrace() {
        Span ofPublish = trace.resume(null, "review enqueue");
        String onTheHeader = withSpanOpen(ofPublish, trace::capture);
        String originalTrace = ofPublish.context().traceId();
        ofPublish.end();

        AtomicReference<String> insideConsume = new AtomicReference<>();
        trace.consuming(onTheHeader, QUEUE, () -> insideConsume.set(trace.capture()));

        assertThat(insideConsume.get()).contains(originalTrace);
    }

    @Test
    @DisplayName("a review re-queued by a job, with no originating request, does not break on capture")
    void scheduledWorkDoesNotBreak() {
        // This is why the header is optional. If capture() threw here, every
        // job-driven re-queue would break at publish time.
        assertThat(trace.capture()).isNull();
    }

    @Test
    @DisplayName("consuming with no earlier context opens its own trace instead of having none")
    void consumingWithoutContextOpensATrace() {
        AtomicBoolean ran = new AtomicBoolean(false);

        trace.consuming(null, QUEUE, () -> ran.set(true));

        assertThat(ran).isTrue();
        assertThat(memory.spans()).isNotEmpty();
    }

    @Test
    @DisplayName("consuming runs the work, and does not merely time the space around it")
    void consumingRunsTheWork() {
        AtomicBoolean processed = new AtomicBoolean(false);

        trace.consuming("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                QUEUE, () -> processed.set(true));

        assertThat(processed).isTrue();
    }

    @Test
    @DisplayName("a failure rises, because the listener is what decides the message's fate")
    void failureRises() {
        assertThatThrownBy(() -> trace.consuming(null, QUEUE, () -> {
            throw new IllegalStateException("database down");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the span closes even when consuming fails, or the trace stays open forever")
    void spanClosesOnFailure() {
        try {
            trace.consuming(null, QUEUE, () -> {
                throw new IllegalStateException("failed");
            });
        } catch (IllegalStateException expected) {
            // what matters is the span having been exported, not the exception
        }

        assertThat(memory.spans())
                .as("a span that was never exported is a span left open")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the failure is recorded on the span, so the dashboard shows the error and not just slowness")
    void failureIsRecordedOnTheSpan() {
        try {
            trace.consuming(null, QUEUE, () -> {
                throw new IllegalStateException("database down");
            });
        } catch (IllegalStateException expected) {
            // same as above
        }

        assertThat(memory.last().getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }

    @Test
    @DisplayName("the span carries the destination, the first filter anyone opening the dashboard uses")
    void spanCarriesTheDestination() {
        trace.consuming(null, "review.dlq", () -> {
        });

        assertThat(memory.last().getAttributes().asMap().toString())
                .contains("review.dlq");
    }

    @Test
    @DisplayName("resuming from empty text behaves like no trace, and does not blow up")
    void resumeFromEmpty() {
        Span span = trace.resume("", "review process");

        assertThat(span).isNotNull();
        span.end();
    }

    @Test
    @DisplayName("a malformed context does not sink the consumption: opens a new trace and carries on")
    void malformedContextDoesNotSinkIt() {
        AtomicBoolean ran = new AtomicBoolean(false);

        trace.consuming("this-is-not-a-traceparent", QUEUE, () -> ran.set(true));

        assertThat(ran)
                .as("a message with a broken header still has to be processed")
                .isTrue();
    }

    @Test
    @DisplayName("the field name is the W3C one, not an invention of ours")
    void fieldIsTheStandard() {
        assertThat(MessageTrace.FIELD).isEqualTo("traceparent");
    }

    private <T> T withSpanOpen(Span span, java.util.function.Supplier<T> action) {
        var context = new io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext();
        try (var ignored = context.maybeScope(span.context())) {
            return action.get();
        }
    }
}
