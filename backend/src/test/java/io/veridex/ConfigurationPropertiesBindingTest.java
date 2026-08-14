package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.config.InfrastructurePropertiesConfiguration;
import io.veridex.shared.infrastructure.config.MinioProperties;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 只加载配置属性装配（不加载整个应用，避免 Security/JPA 依赖）
@SpringBootTest(classes = InfrastructurePropertiesConfiguration.class)
class ConfigurationPropertiesBindingTest {

    @Autowired MinioProperties minio;
    @Autowired OpenSearchProperties openSearch;
    @Autowired EmbeddingProperties embedding;

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
        assertThat(embedding.ollama().model()).isEqualTo("qwen3-embedding");
    }
}
