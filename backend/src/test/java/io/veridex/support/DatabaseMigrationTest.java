package io.veridex.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
                var versions = new java.util.ArrayList<String>();
                while (rows.next()) {
                    versions.add(rows.getString("version"));
                    assertThat(rows.getBoolean("success")).isTrue();
                }
                assertThat(versions).contains("1", "2", "3", "4", "5", "6", "7");
            }
        }
    }

    @Test
    void identitySeedUsersHaveStableIdsAndRoles() throws Exception {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT id, username, role FROM users ORDER BY username
                     """);
             var rows = statement.executeQuery()) {
            var users = new HashMap<String, String>();
            while (rows.next()) {
                users.put(rows.getString("username"), rows.getString("id") + ":" + rows.getString("role"));
            }
            assertThat(users).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "admin", "00000000-0000-0000-0000-000000000001:PLATFORM_ADMIN",
                    "kadmin", "00000000-0000-0000-0000-000000000002:KNOWLEDGE_ADMIN",
                    "employee", "00000000-0000-0000-0000-000000000003:EMPLOYEE"));
        }
    }

    @Test
    void fullSnapshotReleaseMigrationAddsColumnsAndBackfillsActiveFlag() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            var releaseColumns = columns(connection);
            assertColumn(releaseColumns, "index_release.document_count", "integer", null, false, "0");
            assertColumn(releaseColumns, "index_release.chunk_count", "integer", null, false, "0");
            assertColumn(releaseColumns, "index_release.is_active", "boolean", null, false, "false");
            assertThat(releaseColumns).as("dropped document_version_id").doesNotContainKey("index_release.document_version_id");

            try (var statement = connection.prepareStatement("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'index_release_document'
                    """); var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("index_release_document table").isTrue();
            }
        }
    }

    @Test
    void phase3QaMigrationCreatesTablesAndColumns() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(tableNames(connection)).contains(
                    "conversation", "message", "query_run", "retrieval_hit", "generation_run", "citation");

            var qaColumns = new HashMap<String, ColumnContract>();
            try (var statement = connection.prepareStatement("""
                    SELECT table_name, column_name, data_type, character_maximum_length,
                           is_nullable, column_default
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name IN ('conversation', 'message', 'query_run', 'retrieval_hit', 'generation_run', 'citation')
                    """); var rows = statement.executeQuery()) {
                while (rows.next()) {
                    qaColumns.put(rows.getString("table_name") + "." + rows.getString("column_name"),
                            new ColumnContract(rows.getString("data_type"),
                                    rows.getObject("character_maximum_length", Integer.class),
                                    "YES".equals(rows.getString("is_nullable")),
                                    rows.getString("column_default")));
                }
            }
            assertColumn(qaColumns, "conversation.user_id", "uuid", null, false, null);
            assertColumn(qaColumns, "conversation.title", "character varying", 200, false, null);
            assertColumn(qaColumns, "message.role", "character varying", 20, false, null);
            assertColumn(qaColumns, "query_run.status", "character varying", 30, false, null);
            assertColumn(qaColumns, "query_run.knowledge_scope", "jsonb", null, false, "'[]'::jsonb");
            assertColumn(qaColumns, "query_run.refusal_reason", "character varying", 60, true, null);
            assertColumn(qaColumns, "retrieval_hit.channel", "character varying", 10, false, null);
            assertColumn(qaColumns, "retrieval_hit.fusion_score", "double precision", null, true, null);
            assertColumn(qaColumns, "generation_run.duration_ms", "bigint", null, false, "0");
            assertColumn(qaColumns, "citation.validation_status", "character varying", 30, false, null);
        }
    }

    @Test
    void platformColumnsRetainPostgresTypesNullabilityAndDefaults() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            var columns = columns(connection);

            assertColumn(columns, "installation.id", "uuid", null, false, "gen_random_uuid");
            assertColumn(columns, "installation.installation_key", "character varying", 100, false, null);
            assertColumn(columns, "installation.display_name", "character varying", 200, false, null);
            assertColumn(columns, "installation.created_at", "timestamp with time zone", null, false, "now");
            assertColumn(columns, "installation.version", "bigint", null, false, "0");

            assertColumn(columns, "outbox_event.id", "uuid", null, false, "gen_random_uuid");
            assertColumn(columns, "outbox_event.aggregate_type", "character varying", 100, false, null);
            assertColumn(columns, "outbox_event.aggregate_id", "uuid", null, false, null);
            assertColumn(columns, "outbox_event.event_type", "character varying", 200, false, null);
            assertColumn(columns, "outbox_event.payload", "jsonb", null, false, null);
            assertColumn(columns, "outbox_event.schema_version", "integer", null, false, null);
            assertColumn(columns, "outbox_event.occurred_at", "timestamp with time zone", null, false, null);
            assertColumn(columns, "outbox_event.published_at", "timestamp with time zone", null, true, null);
            assertColumn(columns, "outbox_event.attempts", "integer", null, false, "0");
            assertColumn(columns, "outbox_event.last_error", "text", null, true, null);

            assertColumn(columns, "audit_event.id", "uuid", null, false, "gen_random_uuid");
            assertColumn(columns, "audit_event.actor_id", "uuid", null, true, null);
            assertColumn(columns, "audit_event.action", "character varying", 200, false, null);
            assertColumn(columns, "audit_event.resource_type", "character varying", 100, false, null);
            assertColumn(columns, "audit_event.resource_id", "uuid", null, true, null);
            assertColumn(columns, "audit_event.request_id", "character varying", 100, true, null);
            assertColumn(columns, "audit_event.details", "jsonb", null, false, "'{}'::jsonb");
            assertColumn(columns, "audit_event.occurred_at", "timestamp with time zone", null, false, "now");
        }
    }

    @Test
    void platformIndexesAndConstraintsRetainTheirSemantics() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(indexDefinition(connection, "idx_outbox_unpublished"))
                    .contains("occurred_at", "where published_at is null");
            assertThat(indexDefinition(connection, "idx_audit_occurred_at"))
                    .contains("occurred_at desc");

            assertSingleColumnConstraint(connection, "installation", "PRIMARY KEY", "id");
            assertSingleColumnConstraint(connection, "outbox_event", "PRIMARY KEY", "id");
            assertSingleColumnConstraint(connection, "audit_event", "PRIMARY KEY", "id");
            assertSingleColumnConstraint(connection, "installation", "UNIQUE", "installation_key");
        }
    }

    @Test
    void missingDefaultReportsItsColumnInsteadOfThrowingNullPointerException() {
        var columns = Map.of("installation.id", new ColumnContract("uuid", null, false, null));

        assertThatThrownBy(() -> assertColumn(
                        columns, "installation.id", "uuid", null, false, "gen_random_uuid"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("default of installation.id")
                .isNotInstanceOf(NullPointerException.class);
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
                SELECT table_name, column_name, data_type, character_maximum_length,
                       is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('installation', 'outbox_event', 'audit_event', 'index_release')
                """);
                var rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.put(
                        rows.getString("table_name") + "." + rows.getString("column_name"),
                        new ColumnContract(
                                rows.getString("data_type"),
                                rows.getObject("character_maximum_length", Integer.class),
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
            Integer characterMaximumLength,
            boolean nullable,
            String defaultFragment) {
        assertThat(columns).as("column %s", name).containsKey(name);
        var column = columns.get(name);
        assertThat(column.dataType()).as("data type of %s", name).isEqualTo(dataType);
        assertThat(column.characterMaximumLength())
                .as("character maximum length of %s", name)
                .isEqualTo(characterMaximumLength);
        assertThat(column.nullable()).as("nullability of %s", name).isEqualTo(nullable);
        if (defaultFragment == null) {
            assertThat(column.defaultExpression()).as("default of %s", name).isNull();
        } else {
            assertThat(column.defaultExpression()).as("default of %s", name).isNotNull();
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

    private static void assertSingleColumnConstraint(
            Connection connection, String table, String constraintType, String column) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT a.attname AS column_name
                FROM pg_catalog.pg_constraint constraint_definition
                JOIN pg_catalog.pg_class constrained_table
                  ON constrained_table.oid = constraint_definition.conrelid
                JOIN pg_catalog.pg_namespace table_schema
                  ON table_schema.oid = constrained_table.relnamespace
                CROSS JOIN LATERAL unnest(constraint_definition.conkey)
                  WITH ORDINALITY AS key_column(attribute_number, ordinality)
                JOIN pg_catalog.pg_attribute a
                  ON a.attrelid = constrained_table.oid
                 AND a.attnum = key_column.attribute_number
                WHERE table_schema.nspname = 'public'
                  AND constrained_table.relname = ?
                  AND constraint_definition.contype = ?
                ORDER BY constraint_definition.oid, key_column.ordinality
                """)) {
            statement.setString(1, table);
            statement.setString(2, "PRIMARY KEY".equals(constraintType) ? "p" : "u");
            try (var rows = statement.executeQuery()) {
                List<String> constraintColumns = new ArrayList<>();
                while (rows.next()) {
                    constraintColumns.add(rows.getString("column_name"));
                }
                assertThat(constraintColumns)
                        .as("columns of %s constraint on public.%s", constraintType, table)
                        .containsExactly(column);
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

    private record ColumnContract(
            String dataType, Integer characterMaximumLength, boolean nullable, String defaultExpression) {}
}
