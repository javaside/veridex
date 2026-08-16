package io.veridex.shared.infrastructure.config;

import io.veridex.shared.infrastructure.RateLimitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MinioProperties.class, OpenSearchProperties.class, EmbeddingProperties.class,
        RateLimitProperties.class})
public class InfrastructurePropertiesConfiguration {
}
