package io.veridex.shared.observability;

public record RabbitPropagationContext(String traceparent, String tracestate, String requestId) {
}
