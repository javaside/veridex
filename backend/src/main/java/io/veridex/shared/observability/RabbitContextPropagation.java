package io.veridex.shared.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class RabbitContextPropagation {

    static final String TRACEPARENT = "traceparent";
    static final String TRACESTATE = "tracestate";
    static final String REQUEST_ID = "x-request-id";
    static final String EVENT_ID = "x-outbox-event-id";
    static final String TELEMETRY_VERSION = "x-veridex-telemetry-version";
    private static final String CURRENT_VERSION = "1";
    private static final String PERSISTED_TRACEPARENT = "x-veridex-persisted-traceparent";
    private static final String PERSISTED_TRACESTATE = "x-veridex-persisted-tracestate";
    private static final Pattern W3C_TRACEPARENT = Pattern.compile(
            "00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}");
    private static final Pattern TRACESTATE_VALUE = Pattern.compile("[\\x20-\\x7E]{1,512}");
    private static final Pattern TRACESTATE_KEY = Pattern.compile(
            "(?:[a-z0-9][a-z0-9_\\-*/]{0,255}|[a-z0-9][a-z0-9_\\-*/]{0,240}@[a-z0-9][a-z0-9_\\-*/]{0,13})");
    private static final Pattern TRACESTATE_MEMBER_VALUE = Pattern.compile("[\\x20-\\x2B\\x2D-\\x3C\\x3E-\\x7E]{1,256}");
    private static final Pattern REQUEST_ID_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

    private final Tracer tracer;
    private final Propagator propagator;

    public RabbitContextPropagation(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable();
        this.propagator = propagator.getIfAvailable();
    }

    public RabbitPropagationContext captureCurrent() {
        Map<String, String> carrier = new HashMap<>();
        if (tracer != null && propagator != null) {
            Span span = tracer.currentSpan();
            if (span != null) {
                propagator.inject(span.context(), carrier, Map::put);
            }
        }
        return sanitized(carrier.get(TRACEPARENT), carrier.get(TRACESTATE), MDC.get("requestId"));
    }

    public static void writeHeaders(RabbitPropagationContext context, UUID eventId, MessageProperties properties) {
        properties.setHeader(TELEMETRY_VERSION, CURRENT_VERSION);
        if (eventId != null) {
            properties.setHeader(EVENT_ID, eventId.toString());
        }
        RabbitPropagationContext valid = context == null
                ? new RabbitPropagationContext(null, null, null)
                : sanitized(context.traceparent(), context.tracestate(), context.requestId());
        setIfPresent(properties, TRACEPARENT, valid.traceparent());
        setIfPresent(properties, TRACESTATE, valid.tracestate());
        setIfPresent(properties, REQUEST_ID, valid.requestId());
        setIfPresent(properties, PERSISTED_TRACEPARENT, valid.traceparent());
        setIfPresent(properties, PERSISTED_TRACESTATE, valid.tracestate());
    }

    public static void restorePersistedTraceHeaders(MessageProperties properties) {
        if (!CURRENT_VERSION.equals(header(properties, TELEMETRY_VERSION))
                || header(properties, EVENT_ID) == null) {
            return;
        }
        restore(properties, PERSISTED_TRACEPARENT, TRACEPARENT);
        restore(properties, PERSISTED_TRACESTATE, TRACESTATE);
    }

    public static RabbitPropagationContext extract(MessageProperties properties) {
        if (properties == null || !CURRENT_VERSION.equals(header(properties, TELEMETRY_VERSION))) {
            return new RabbitPropagationContext(null, null, null);
        }
        return sanitized(header(properties, TRACEPARENT), header(properties, TRACESTATE),
                header(properties, REQUEST_ID));
    }

    private static RabbitPropagationContext sanitized(String traceparent, String tracestate, String requestId) {
        return new RabbitPropagationContext(
                valid(traceparent, W3C_TRACEPARENT) ? traceparent : null,
                validTracestate(tracestate) ? tracestate : null,
                valid(requestId, REQUEST_ID_VALUE) ? requestId : null);
    }

    private static boolean validTracestate(String value) {
        if (!valid(value, TRACESTATE_VALUE)) {
            return false;
        }
        String[] members = value.split(",", -1);
        if (members.length > 32) {
            return false;
        }
        for (String member : members) {
            String trimmed = member.trim();
            int separator = trimmed.indexOf('=');
            if (separator < 1 || separator == trimmed.length() - 1
                    || !TRACESTATE_KEY.matcher(trimmed.substring(0, separator)).matches()
                    || !TRACESTATE_MEMBER_VALUE.matcher(trimmed.substring(separator + 1)).matches()) {
                return false;
            }
        }
        return true;
    }

    private static boolean valid(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches();
    }

    private static String header(MessageProperties properties, String name) {
        Object value = properties.getHeader(name);
        return value instanceof String string ? string : null;
    }

    private static void restore(MessageProperties properties, String persistedName, String headerName) {
        String persisted = header(properties, persistedName);
        properties.getHeaders().remove(persistedName);
        if (persisted != null) {
            properties.setHeader(headerName, persisted);
        } else {
            properties.getHeaders().remove(headerName);
        }
    }

    private static void setIfPresent(MessageProperties properties, String name, String value) {
        if (value != null) {
            properties.setHeader(name, value);
        }
    }
}
