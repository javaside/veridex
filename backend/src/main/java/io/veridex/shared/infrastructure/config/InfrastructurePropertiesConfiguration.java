package io.veridex.shared.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MinioProperties.class, OpenSearchProperties.class, EmbeddingProperties.class})
public class InfrastructurePropertiesConfiguration {
}
