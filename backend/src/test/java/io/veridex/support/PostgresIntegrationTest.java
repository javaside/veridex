package io.veridex.support;

import io.veridex.VeridexApplication;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 完整应用栈集成测试基类：启动完整 {@link VeridexApplication} 上下文所需的全部基础设施容器。
 *
 * <p>{@code PostgresIntegrationTest} 这个类名是历史遗留（最初只起 PostgreSQL），现在它同时提供
 * PostgreSQL / RabbitMQ / MinIO / OpenSearch 四个共享单例容器，让完整应用上下文（会装配
 * {@code MinioObjectStorage}、RabbitMQ 监听器、OpenSearch 客户端等 bean）在任何干净环境都能起来，
 * 不再依赖 localhost 上预置的 compose 基础设施。</p>
 */
@SpringBootTest(classes = VeridexApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "veridex.chat.provider=deterministic"})
public abstract class PostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final RabbitMQContainer RABBITMQ;
    static final GenericContainer<?> MINIO;
    static final OpenSearchContainer<?> OPENSEARCH;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("veridex")
                .withUsername("veridex")
                .withPassword("veridex");
        RABBITMQ = new RabbitMQContainer("rabbitmq:4-management-alpine");
        MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-07-23T15-54-02Z"))
                .withCommand("server", "/data", "--console-address", ":9001")
                .withEnv("MINIO_ROOT_USER", "veridex")
                .withEnv("MINIO_ROOT_PASSWORD", "veridex-local-secret")
                .withExposedPorts(9000, 9001);
        OPENSEARCH = new OpenSearchContainer<>(
                DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
                .withEnv("DISABLE_SECURITY_PLUGIN", "true");

        POSTGRES.start();
        RABBITMQ.start();
        MINIO.start();
        OPENSEARCH.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            OPENSEARCH.stop();
            MINIO.stop();
            RABBITMQ.stop();
            POSTGRES.stop();
        }));
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // RabbitMQ（默认 guest/guest，RabbitMQ 4 的 rabbitmqadmin 语法变更导致 withUser 不兼容）
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBITMQ.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        // MinIO（与应用默认凭据一致）
        registry.add("veridex.storage.minio.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("veridex.storage.minio.access-key", () -> "veridex");
        registry.add("veridex.storage.minio.secret-key", () -> "veridex-local-secret");
        // OpenSearch
        registry.add("veridex.search.opensearch.uris[0]", OPENSEARCH::getHttpHostAddress);
    }
}
