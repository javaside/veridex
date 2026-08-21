package io.veridex.support;

import io.veridex.VeridexApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(classes = VeridexApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "veridex.chat.provider=deterministic"})
public abstract class PostgresIntegrationTest {

    /**
     * 共享单例 PostgreSQL 容器：静态初始化只执行一次，所有继承类复用同一容器与
     * 同一 JDBC URL，避免 Spring context 缓存指向已停止的容器（每个测试类各起
     * 一个 @Container 会导致此问题）。
     */
    static final PostgreSQLContainer<?> POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("veridex")
                .withUsername("veridex")
                .withPassword("veridex");
        POSTGRES.start();
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop));
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
