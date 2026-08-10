package io.veridex.support;

import io.minio.MinioClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class MinioContainerConfiguration {

    private static final String ACCESS_KEY = "veridex";
    private static final String SECRET_KEY = "veridex-local-secret";

    @Bean(destroyMethod = "stop")
    GenericContainer<?> minioContainer() {
        return new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-07-23T15-54-02Z"))
                .withCommand("server", "/data", "--console-address", ":9001")
                .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                .withExposedPorts(9000, 9001);
    }

    @Bean
    MinioClient minioClient(GenericContainer<?> minioContainer) {
        String endpoint = "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(ACCESS_KEY, SECRET_KEY)
                .build();
    }
}
