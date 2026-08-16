package io.veridex.trace.infrastructure;

import io.veridex.shared.observability.TelemetryErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcTraceBodyMaintenanceRepository {

    private static final String DELETE_EXPIRED = """
            WITH doomed AS (
                SELECT query_run_id
                FROM trace_body
                WHERE expires_at < ?
                ORDER BY expires_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM trace_body body
            USING doomed
            WHERE body.query_run_id = doomed.query_run_id
            """;

    private static final String BOUNDED_QUERY_RUN_CODES = TelemetryErrorCode.persistedQueryRunCodes().stream()
            .sorted()
            .map(code -> "'" + code + "'")
            .collect(Collectors.joining(", "));

    private static final String SCRUB_QUERY_RUNS = """
            WITH dirty AS (
                SELECT id
                FROM query_run
                WHERE question IS DISTINCT FROM '[REDACTED]'
                   OR normalized_question IS DISTINCT FROM '[REDACTED]'
                   OR (error IS NOT NULL AND error NOT IN (%1$s))
                ORDER BY created_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE query_run run
            SET question = '[REDACTED]',
                normalized_question = '[REDACTED]',
                error = CASE WHEN error IN (%1$s) THEN error ELSE NULL END
            FROM dirty
            WHERE run.id = dirty.id
            """.formatted(BOUNDED_QUERY_RUN_CODES);

    private final JdbcTemplate jdbc;

    public JdbcTraceBodyMaintenanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpired(Instant now, int limit) {
        requirePositive(limit);
        return jdbc.update(DELETE_EXPIRED, Timestamp.from(now), limit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int scrubQueryRuns(int limit) {
        requirePositive(limit);
        return jdbc.update(SCRUB_QUERY_RUNS, limit);
    }

    private static void requirePositive(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("maintenance limit must be positive");
        }
    }
}
