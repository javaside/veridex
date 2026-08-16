package io.veridex.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.ObjectProvider;

class RabbitContextPropagationTest {

    private static final String TRACEPARENT =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void capturesCurrentTraceCarrierAndRequestId() {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        Propagator propagator = new Propagator() {
            @Override
            public java.util.List<String> fields() {
                return java.util.List.of("traceparent", "tracestate");
            }

            @Override
            public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
                setter.set(carrier, "traceparent", TRACEPARENT);
                setter.set(carrier, "tracestate", "vendor=value");
            }

            @Override
            public <C> Span.Builder extract(C carrier, Getter<C> getter) {
                return null;
            }
        };
        @SuppressWarnings("unchecked") ObjectProvider<Tracer> tracerProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<Propagator> propagatorProvider = mock(ObjectProvider.class);
        when(tracerProvider.getIfAvailable()).thenReturn(tracer);
        when(propagatorProvider.getIfAvailable()).thenReturn(propagator);
        MDC.put("requestId", "request-123");
        try {
            assertThat(new RabbitContextPropagation(tracerProvider, propagatorProvider).captureCurrent())
                    .isEqualTo(new RabbitPropagationContext(TRACEPARENT, "vendor=value", "request-123"));
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    void validContextRoundTripsThroughVersionedHeaders() {
        MessageProperties properties = new MessageProperties();
        UUID eventId = UUID.randomUUID();
        RabbitPropagationContext context = new RabbitPropagationContext(
                TRACEPARENT, "vendor=value", "request-123");

        RabbitContextPropagation.writeHeaders(context, eventId, properties);

        assertThat(RabbitContextPropagation.extract(properties)).isEqualTo(context);
        assertThat((Object) properties.getHeader("x-outbox-event-id")).isEqualTo(eventId.toString());
        assertThat((Object) properties.getHeader("x-veridex-telemetry-version")).isEqualTo("1");
    }

    @Test
    void persistedTraceHeadersWinAfterTransportInjectionWithoutLeakingInternalHeaders() {
        MessageProperties properties = new MessageProperties();
        RabbitContextPropagation.writeHeaders(new RabbitPropagationContext(
                TRACEPARENT, "vendor=value", "request-123"), UUID.randomUUID(), properties);
        properties.setHeader("traceparent",
                "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        properties.setHeader("tracestate", "transport=value");

        RabbitContextPropagation.restorePersistedTraceHeaders(properties);

        assertThat((Object) properties.getHeader("traceparent")).isEqualTo(TRACEPARENT);
        assertThat((Object) properties.getHeader("tracestate")).isEqualTo("vendor=value");
        assertThat(properties.getHeaders().keySet()).noneMatch(name -> name.startsWith("x-veridex-persisted-"));
    }

    @Test
    void malformedAndOverlongValuesAreIgnoredIndependently() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-veridex-telemetry-version", "1");
        properties.setHeader("traceparent", "00-not-a-traceparent");
        properties.setHeader("tracestate", "a=" + "x".repeat(511));
        properties.setHeader("x-request-id", "request id with spaces");

        assertThat(RabbitContextPropagation.extract(properties))
                .isEqualTo(new RabbitPropagationContext(null, null, null));
    }

    @Test
    void unknownTelemetryVersionIgnoresAllPropagationHeaders() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-veridex-telemetry-version", "2");
        properties.setHeader("traceparent", TRACEPARENT);
        properties.setHeader("tracestate", "vendor=value");
        properties.setHeader("x-request-id", "request-123");

        assertThat(RabbitContextPropagation.extract(properties))
                .isEqualTo(new RabbitPropagationContext(null, null, null));
    }

    @Test
    void writingInvalidContextOmitsInvalidValuesButKeepsEnvelopeHeaders() {
        MessageProperties properties = new MessageProperties();
        UUID eventId = UUID.randomUUID();

        RabbitContextPropagation.writeHeaders(new RabbitPropagationContext(
                "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "bad\nstate", "x".repeat(101)), eventId, properties);

        assertThat(properties.getHeaders())
                .doesNotContainKeys("traceparent", "tracestate", "x-request-id")
                .containsEntry("x-outbox-event-id", eventId.toString())
                .containsEntry("x-veridex-telemetry-version", "1");
    }
}
