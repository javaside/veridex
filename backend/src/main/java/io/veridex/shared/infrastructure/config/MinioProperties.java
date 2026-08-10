package io.veridex.shared.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.storage.minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket) {
}
