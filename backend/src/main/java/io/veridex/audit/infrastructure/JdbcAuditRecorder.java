package io.veridex.audit.infrastructure;

import io.veridex.audit.api.AuditRecorder;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JdbcAuditRecorder implements AuditRecorder {

    private final JdbcTemplate jdbc;

    public JdbcAuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID actorId, String action, String resourceType, UUID resourceId,
                       String requestId, Map<String, Object> details) {
        jdbc.update("""
                INSERT INTO audit_event (actor_id, action, resource_type, resource_id, request_id, details)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, actorId, action, resourceType, resourceId, requestId, toJson(details));
    }

    private static String toJson(Map<String, Object> details) {
        try {
            return new JsonMapper().writeValueAsString(details);
        } catch (Exception e) {
            return "{}";
        }
    }
}
