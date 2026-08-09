package io.veridex.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.HashSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DatabaseMigrationTest extends PostgresIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void flywayCreatesPlatformBaselineTables() throws Exception {
        try (Connection connection = dataSource.getConnection();
                var tables = connection.getMetaData()
                        .getTables(null, "public", "%", new String[] {"TABLE"})) {
            var names = new HashSet<String>();
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME"));
            }
            assertThat(names).contains("installation", "outbox_event", "audit_event");
        }
    }
}
