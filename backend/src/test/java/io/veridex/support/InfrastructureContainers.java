package io.veridex.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

abstract class InfrastructureContainers {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4-management-alpine");
    static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-07-23T15-54-02Z"))
            .withCommand("server", "/data", "--console-address", ":9001")
            .withEnv("MINIO_ROOT_USER", "veridex")
            .withEnv("MINIO_ROOT_PASSWORD", "veridex-local-secret")
            .withExposedPorts(9000, 9001);
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>(
            "opensearchproject/opensearch:3.2.0");

    private static final ContainerLifecycle LIFECYCLE = new ContainerLifecycle();

    @BeforeAll
    static void startInfrastructure() {
        start(POSTGRES);
        start(RABBITMQ);
        start(MINIO);
        start(OPENSEARCH);
    }

    @AfterAll
    static void stopInfrastructure() {
        LIFECYCLE.stopStartedContainers();
    }

    private static void start(Startable container) {
        LIFECYCLE.start(container);
    }
}
