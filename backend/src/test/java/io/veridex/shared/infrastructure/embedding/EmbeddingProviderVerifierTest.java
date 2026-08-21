package io.veridex.shared.infrastructure.embedding;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.embedding.EmbeddingProviderConfiguration.EmbeddingProviderVerifier;
import org.junit.jupiter.api.Test;

/**
 * embedding provider 合法值校验：只允许 deterministic/ollama，非法值启动失败。
 */
class EmbeddingProviderVerifierTest {

    private static EmbeddingProviderVerifier verifier(String provider) {
        return new EmbeddingProviderVerifier(new EmbeddingProperties(provider, 128,
                new EmbeddingProperties.Ollama("http://localhost:11434", "qwen3-embedding")));
    }

    @Test
    void acceptsDeterministicAndOllama() {
        assertThatCode(() -> verifier("deterministic").afterPropertiesSet()).doesNotThrowAnyException();
        assertThatCode(() -> verifier("ollama").afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownProvider() {
        assertThatThrownBy(() -> verifier("openai").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai");
    }

    @Test
    void rejectsNullProvider() {
        assertThatThrownBy(() -> verifier(null).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }
}
