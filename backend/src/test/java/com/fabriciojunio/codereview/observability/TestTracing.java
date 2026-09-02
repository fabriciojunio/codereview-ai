package com.fabriciojunio.codereview.observability;

import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real {@link MessageTrace}, with the real OpenTelemetry and no collector
 * behind it.
 *
 * <p>A mock does not work here, for two reasons. The first is that
 * {@code consuming} takes the work and is the one that runs it: a mock would
 * return null and swallow the call, and the test would pass without ever having
 * processed the message. The second is that what needs proving is W3C
 * propagation, and the format only exists in the real propagator.
 *
 * <p>Spans land in an in-memory list, so a test can check what was opened and
 * how it linked up without starting any collector.
 */
public final class TestTracing {

    private TestTracing() {
    }

    /** Keeps exported spans in memory, for the test to inspect. */
    public static final class Memory implements SpanExporter {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            spans.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        public List<SpanData> spans() {
            return spans;
        }

        public SpanData last() {
            return spans.get(spans.size() - 1);
        }
    }

    /** The trace helper, the tracer under it, and the memory spans fall into. */
    public record Setup(MessageTrace trace, io.micrometer.tracing.Tracer tracer, Memory memory) {
    }

    public static Setup build() {
        Memory memory = new Memory();

        SdkTracerProvider provider = SdkTracerProvider.builder()
                // A simple processor, not a batching one: in a test the span has
                // to be in memory as soon as it closes, or the assertion races
                // the export and the test turns flaky.
                .addSpanProcessor(SimpleSpanProcessor.create(memory))
                .build();

        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        var currentContext = new OtelCurrentTraceContext();
        var tracer = new OtelTracer(otel.getTracer("test"), currentContext, event -> {
        });
        Propagator propagator = new OtelPropagator(otel.getPropagators(), otel.getTracer("test"));

        return new Setup(new MessageTrace(tracer, propagator), tracer, memory);
    }

    /** Shortcut for whoever just needs a trace helper that works. */
    public static MessageTrace withoutCollector() {
        return build().trace();
    }
}
