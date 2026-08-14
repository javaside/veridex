package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.embedding.DeterministicEmbeddingModel;
import io.veridex.shared.infrastructure.embedding.OllamaEmbeddingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EmbeddingProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean("embeddingProperties", EmbeddingProperties.class, () -> new EmbeddingProperties(
                    "deterministic", 128,
                    new EmbeddingProperties.Ollama("http://localhost:11434", "qwen3-embedding")))
            .withUserConfiguration(DeterministicEmbeddingModel.class, OllamaEmbeddingConfiguration.class);

    @Test
    void deterministicProviderRegistersOnlyDeterministicModel() {
        runner.withPropertyValues("veridex.embedding.provider=deterministic").run(context -> {
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(DeterministicEmbeddingModel.class);
        });
    }

    @Test
    void ollamaProviderRegistersOnlyOllamaModel() {
        runner.withPropertyValues("veridex.embedding.provider=ollama").run(context -> {
            assertThat(context).hasSingleBean(EmbeddingModel.class);
            assertThat(context.getBean(EmbeddingModel.class)).isInstanceOf(OllamaEmbeddingModel.class);
        });
    }
}
