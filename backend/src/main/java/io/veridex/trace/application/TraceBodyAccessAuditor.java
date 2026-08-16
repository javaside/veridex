package io.veridex.trace.application;

import io.veridex.audit.api.AuditRecorder;
import io.veridex.shared.observability.MetricName;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TraceBodyAccessAuditor {
    public static final String READ = "trace.body.read";
    public static final String DENIED = "trace.body.read_denied";
    public static final String NOT_FOUND = "trace.body.not_found";
    public static final String DECRYPT_FAILED = "trace.body.decrypt_failed";

    private final AuditRecorder recorder;
    private final VeridexObservability observability;

    public TraceBodyAccessAuditor(AuditRecorder recorder, VeridexObservability observability) {
        this.recorder = recorder;
        this.observability = observability;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, String action, UUID runId, String requestId, Map<String, Object> details) {
        recorder.record(actorId, action, "trace_body", runId, requestId, details == null ? Map.of() : details);
        if (DENIED.equals(action)) {
            observability.increment(MetricName.TRACE_BODY_DENIED,
                    TelemetryTag.traceBodyOutcome(TelemetryOutcome.TraceBodyOutcome.DENIED),
                    TelemetryTag.deniedReason(deniedReason(details)));
        }
    }

    private static TelemetryOutcome.TraceBodyDeniedReason deniedReason(Map<String, Object> details) {
        return details != null && "INVALID_REASON".equals(details.get("code"))
                ? TelemetryOutcome.TraceBodyDeniedReason.INVALID_REASON
                : TelemetryOutcome.TraceBodyDeniedReason.UNAUTHORIZED;
    }
}
