package io.veridex.trace.application;

import io.veridex.audit.api.AuditRecorder;
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

    public TraceBodyAccessAuditor(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID actorId, String action, UUID runId, String requestId, Map<String, Object> details) {
        recorder.record(actorId, action, "trace_body", runId, requestId, details == null ? Map.of() : details);
    }
}
