package io.veridex.audit.api;

import java.util.Map;
import java.util.UUID;

public interface AuditRecorder {
    void record(UUID actorId, String action, String resourceType, UUID resourceId,
                String requestId, Map<String, Object> details);
}
