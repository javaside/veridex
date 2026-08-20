package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.RateLimitProperties;
import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.config.InfrastructurePropertiesConfiguration;
import io.veridex.shared.infrastructure.config.MinioProperties;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

// 只加载配置属性装配（不加载整个应用，避免 Security/JPA 依赖）
@SpringBootTest(classes = InfrastructurePropertiesConfiguration.class)
class ConfigurationPropertiesBindingTest {

    @Autowired MinioProperties minio;
    @Autowired OpenSearchProperties openSearch;
    @Autowired EmbeddingProperties embedding;
    @Autowired RateLimitProperties rateLimit;

    @Test
    void minioPropertiesBindWithDefaults() {
        assertThat(minio.endpoint()).isEqualTo("http://localhost:9000");
        assertThat(minio.accessKey()).isEqualTo("veridex");
        assertThat(minio.secretKey()).isEqualTo("veridex-local-secret");
        assertThat(minio.bucket()).isEqualTo("veridex-documents");
    }

    @Test
    void openSearchPropertiesBindWithDefaults() {
        assertThat(openSearch.uris()).containsExactly("http://localhost:9200");
        assertThat(openSearch.indexPrefix()).isEqualTo("veridex");
    }

    @Test
    void embeddingPropertiesBindWithDefaults() {
        assertThat(embedding.provider()).isEqualTo("deterministic");
        assertThat(embedding.dimensions()).isEqualTo(128);
        assertThat(embedding.ollama().baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(embedding.ollama().model()).isEqualTo("qwen3-embedding:0.6b");
    }

    @Test
    void rateLimitDefaultsToSixHundredRequestsPerMinute() {
        assertThat(rateLimit.getRateLimitPerMinute()).isEqualTo(600);
    }

    @Test
    void rateLimitBindsExplicitConfigurationOverride() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "rate-limit-test", Map.of("veridex.api.rate-limit-per-minute", "42")));

        RateLimitProperties bound = Binder.get(environment)
                .bind("veridex.api", RateLimitProperties.class)
                .get();

        assertThat(bound.getRateLimitPerMinute()).isEqualTo(42);
    }
}
