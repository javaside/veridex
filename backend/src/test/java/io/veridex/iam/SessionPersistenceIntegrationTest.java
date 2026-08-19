package io.veridex.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Session 外部化（spec §4.4）：登录后 Session 必须落到 SPRING_SESSION 表，
 * 属性（用户）落到 SPRING_SESSION_ATTRIBUTES——多副本/重启共享登录态的物理证据。
 */
class SessionPersistenceIntegrationTest extends PostgresIntegrationTest {

    @LocalServerPort int port;

    @Autowired JdbcTemplate jdbc;

    @Test
    void loginPersistsSessionToDatabase() throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + port
                                + "/api/auth/login?username=admin&password=veridex"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
        var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isLessThan(300);

        // Session 行落库，且未被标记过期（EXPIRY_TIME 为 epoch 毫秒 BIGINT，与当前毫秒比较）
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM SPRING_SESSION WHERE EXPIRY_TIME > EXTRACT(EPOCH FROM NOW()) * 1000",
                Integer.class);
        assertThat(active).isGreaterThanOrEqualTo(1);

        // 登录用户属性进入属性表（principal_name = admin）
        List<String> principals = jdbc.queryForList(
                "SELECT PRINCIPAL_NAME FROM SPRING_SESSION WHERE PRINCIPAL_NAME = 'admin'", String.class);
        assertThat(principals).isNotEmpty();
    }

    @Test
    void sessionSchemaHasExpiryIndex() {
        // 官方 schema 的 IX2 索引（EXPIRY_TIME）支撑每分钟清理任务。
        // PostgreSQL 将未加引号的标识符折叠为小写，pg_indexes 中记录为 spring_session_ix2。
        Integer indexes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'spring_session_ix2'", Integer.class);
        assertThat(indexes).isEqualTo(1);
    }
}
