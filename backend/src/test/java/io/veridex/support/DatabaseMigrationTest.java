package io.veridex.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DatabaseMigrationTest extends PostgresIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void flywayAppliesPlatformBaselineMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableNames(connection))
                    .contains("installation", "outbox_event", "audit_event", "flyway_schema_history");

            try (var statement = connection.prepareStatement("""
                    SELECT version, success
                    FROM flyway_schema_history
                    WHERE version IS NOT NULL
                    ORDER BY installed_rank
                    """);
                    var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("version")).isEqualTo("1");
                assertThat(rows.getBoolean("success")).isTrue();
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void platformColumnsRetainPostgresTypesNullabilityAndDefaults() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            var columns = columns(connection);

            assertColumn(columns, "installation.id", "uuid", false, "gen_random_uuid");
            assertColumn(columns, "installation.installation_key", "character varying", false, null);
            assertColumn(columns, "installation.display_name", "character varying", false, null);
            assertColumn(columns, "installation.created_at", "timestamp with time zone", false, "now");
            assertColumn(columns, "installation.version", "bigint", false, "0");

            assertColumn(columns, "outbox_event.id", "uuid", false, "gen_random_uuid");
            assertColumn(columns, "outbox_event.aggregate_type", "character varying", false, null);
            assertColumn(columns, "outbox_event.aggregate_id", "uuid", false, null);
            assertColumn(columns, "outbox_event.event_type", "character varying", false, null);
            assertColumn(columns, "outbox_event.payload", "jsonb", false, null);
            assertColumn(columns, "outbox_event.schema_version", "integer", false, null);
            assertColumn(columns, "outbox_event.occurred_at", "timestamp with time zone", false, null);
            assertColumn(columns, "outbox_event.published_at", "timestamp with time zone", true, null);
            assertColumn(columns, "outbox_event.attempts", "integer", false, "0");
            assertColumn(columns, "outbox_event.last_error", "text", true, null);

            assertColumn(columns, "audit_event.id", "uuid", false, "gen_random_uuid");
            assertColumn(columns, "audit_event.actor_id", "uuid", true, null);
            assertColumn(columns, "audit_event.action", "character varying", false, null);
            assertColumn(columns, "audit_event.resource_type", "character varying", false, null);
            assertColumn(columns, "audit_event.resource_id", "uuid", true, null);
            assertColumn(columns, "audit_event.request_id", "character varying", true, null);
            assertColumn(columns, "audit_event.details", "jsonb", false, "'{}'::jsonb");
            assertColumn(columns, "audit_event.occurred_at", "timestamp with time zone", false, "now");
        }
    }

    @Test
    void platformIndexesAndUniqueConstraintRetainTheirSemantics() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(indexDefinition(connection, "idx_outbox_unpublished"))
                    .contains("occurred_at", "where published_at is null");
            assertThat(indexDefinition(connection, "idx_audit_occurred_at"))
                    .contains("occurred_at desc");
            assertThat(hasUniqueConstraint(connection, "installation", "installation_key")).isTrue();
        }
    }

    private static HashSet<String> tableNames(Connection connection) throws SQLException {
        try (var tables = connection.getMetaData()
                .getTables(null, "public", "%", new String[] {"TABLE"})) {
            var names = new HashSet<String>();
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME"));
            }
            return names;
        }
    }

    private static Map<String, ColumnContract> columns(Connection connection) throws SQLException {
        var columns = new HashMap<String, ColumnContract>();
        try (var statement = connection.prepareStatement("""
                SELECT table_name, column_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('installation', 'outbox_event', 'audit_event')
                """);
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.put(
                        rows.getString("table_name") + "." + rows.getString("column_name"),
                        new ColumnContract(
                                rows.getString("data_type"),
                                "YES".equals(rows.getString("is_nullable")),
                                rows.getString("column_default")));
            }
        }
        return columns;
    }

    private static void assertColumn(
            Map<String, ColumnContract> columns,
            String name,
            String dataType,
            boolean nullable,
            String defaultFragment) {
        assertThat(columns).as("column %s", name).containsKey(name);
        var column = columns.get(name);
        assertThat(column.dataType()).as("data type of %s", name).isEqualTo(dataType);
        assertThat(column.nullable()).as("nullability of %s", name).isEqualTo(nullable);
        if (defaultFragment == null) {
            assertThat(column.defaultExpression()).as("default of %s", name).isNull();
        } else {
            assertThat(canonicalSql(column.defaultExpression()))
                    .as("default of %s", name)
                    .contains(canonicalSql(defaultFragment));
        }
    }

    private static String indexDefinition(Connection connection, String indexName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT pg_get_indexdef(indexrelid) AS definition
                FROM pg_index
                WHERE indexrelid = to_regclass(?)
                """)) {
            statement.setString(1, "public." + indexName);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("index %s", indexName).isTrue();
                return canonicalSql(rows.getString("definition"));
            }
        }
    }

    private static boolean hasUniqueConstraint(Connection connection, String table, String column)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON kcu.constraint_schema = tc.constraint_schema
                     AND kcu.constraint_name = tc.constraint_name
                    WHERE tc.table_schema = 'public'
                      AND tc.table_name = ?
                      AND tc.constraint_type = 'UNIQUE'
                      AND kcu.column_name = ?
                )
                """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static String canonicalSql(String sql) {
        return sql.toLowerCase(Locale.ROOT)
                .replace('"', ' ')
                .replaceAll("[(),]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record ColumnContract(String dataType, boolean nullable, String defaultExpression) {}
}
