package io.veridex.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.support.PostgresIntegrationTest;
import io.veridex.trace.infrastructure.JdbcTraceBodyMaintenanceRepository;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class TraceBodyCleanupIntegrationTest extends PostgresIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcTraceBodyMaintenanceRepository maintenance;

    private final List<UUID> runIds = new ArrayList<>();

    /**
     * {@code scrubQueryRuns} 是全表扫描：它按「question/normalized_question 非 [REDACTED] 或
     * error 超出有界码表」判定 dirty。共享 PostgreSQL 容器里，其它集成测试（如 QA 问答）会遗留
     * 真实的 query_run 行，这些行也会被当作 dirty，导致本测试「只 scrub 自己那一行」的断言随
     * 执行顺序飘移。测试前清空 query_run / trace_body（trace_body 外键引用 query_run，须先删），
     * 保证每个用例都在干净表上运行，与执行顺序无关。
     */
    @BeforeEach
    void clearTables() {
        jdbc.update("DELETE FROM trace_body");
        jdbc.update("DELETE FROM query_run");
    }

    @AfterEach
    void cleanupRows() {
        for (UUID runId : runIds) {
            jdbc.update("DELETE FROM query_run WHERE id = ?", runId);
        }
    }

    @Test
    void expiredDeletionIsBoundedRepeatableAndLeavesUnexpiredRows() {
        Instant now = Instant.now();
        UUID oldest = insertBody(now.minusSeconds(30));
        UUID middle = insertBody(now.minusSeconds(20));
        UUID newest = insertBody(now.minusSeconds(10));
        UUID unexpired = insertBody(now.plusSeconds(60));

        assertThat(maintenance.deleteExpired(now, 2)).isEqualTo(2);
        assertThat(existingBodies()).containsExactlyInAnyOrder(newest, unexpired);

        assertThat(maintenance.deleteExpired(now, 2)).isEqualTo(1);
        assertThat(existingBodies()).containsExactly(unexpired);
        assertThat(maintenance.deleteExpired(now, 2)).isZero();
        assertThat(existingBodies()).containsExactly(unexpired);
        assertThat(List.of(oldest, middle)).doesNotContain(unexpired);
    }

    @Test
    void scrubOnlyRewritesAccidentalPlaintextAndUnboundedErrors() {
        UUID dirty = insertRun("accidental question", "accidental normalized", "raw exception details");
        UUID bounded = insertRun("[REDACTED]", "[REDACTED]", "MODEL_ERROR");
        UUID emptyError = insertRun("[REDACTED]", "[REDACTED]", null);

        assertThat(maintenance.scrubQueryRuns(1)).isEqualTo(1);
        assertThat(queryRun(dirty)).containsExactly("[REDACTED]", "[REDACTED]", null);
        assertThat(maintenance.scrubQueryRuns(10)).isZero();
        assertThat(queryRun(bounded)).containsExactly("[REDACTED]", "[REDACTED]", "MODEL_ERROR");
        assertThat(queryRun(emptyError)).containsExactly("[REDACTED]", "[REDACTED]", null);
    }

    @Test
    void expiredDeletionSkipsRowsLockedByAnotherTransaction() throws Exception {
        Instant now = Instant.now();
        UUID locked = insertBody(now.minusSeconds(20));
        UUID available = insertBody(now.minusSeconds(10));

        try (Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT query_run_id FROM trace_body WHERE query_run_id = ? FOR UPDATE")) {
                statement.setObject(1, locked);
                statement.executeQuery();
            }

            assertThat(maintenance.deleteExpired(now, 1)).isEqualTo(1);
            assertThat(existingBodies()).containsExactly(locked);
            connection.rollback();
        }

        assertThat(maintenance.deleteExpired(now, 1)).isEqualTo(1);
        assertThat(existingBodies()).isEmpty();
        assertThat(available).isNotEqualTo(locked);
    }

    private UUID insertBody(Instant expiresAt) {
        UUID runId = insertRun("[REDACTED]", "[REDACTED]", null);
        jdbc.update("""
                INSERT INTO trace_body (query_run_id, capture_policy, encrypted_body, encryption_key_id,
                                        nonce, schema_version, created_at, expires_at)
                VALUES (?, 'ALL', ?, 'test-key', ?, 1, now(), ?)
                """, runId, new byte[]{1}, new byte[12], java.sql.Timestamp.from(expiresAt));
        return runId;
    }

    private UUID insertRun(String question, String normalizedQuestion, String error) {
        UUID runId = UUID.randomUUID();
        runIds.add(runId);
        jdbc.update("""
                INSERT INTO query_run (id, user_id, question, normalized_question, status, error, created_at)
                VALUES (?, ?, ?, ?, 'FAILED', ?, now())
                """, runId, USER_ID, question, normalizedQuestion, error);
        return runId;
    }

    private List<UUID> existingBodies() {
        return jdbc.queryForList("SELECT query_run_id FROM trace_body", UUID.class).stream()
                .filter(runIds::contains)
                .toList();
    }

    private List<Object> queryRun(UUID runId) {
        return jdbc.queryForObject("""
                SELECT question, normalized_question, error
                FROM query_run WHERE id = ?
                """, (resultSet, rowNumber) -> java.util.Arrays.asList(
                resultSet.getString("question"),
                resultSet.getString("normalized_question"),
                resultSet.getString("error")), runId);
    }
}
