package io.veridex.shared.infrastructure.embedding;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class EmbeddingDimensionValidatorTest {

    @Test
    void passesWhenProbeMatchesConfiguredDimension() {
        var embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyString())).thenReturn(new float[1024]);
        var props = new EmbeddingProperties("ollama", 1024,
                new EmbeddingProperties.Ollama("http://localhost:11434", "qwen3-embedding"));

        var validator = new EmbeddingDimensionValidator(embeddings, props);

        assertThatCode(() -> validator.run(null)).doesNotThrowAnyException();
    }

    @Test
    void failsWhenProbeDiffersFromConfiguredDimension() {
        var embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyString())).thenReturn(new float[768]);
        var props = new EmbeddingProperties("ollama", 1024,
                new EmbeddingProperties.Ollama("http://localhost:11434", "qwen3-embedding"));

        var validator = new EmbeddingDimensionValidator(embeddings, props);

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1024");
    }

    @Test
    void failsWhenEmbeddingReturnsNullProbe() {
        var embeddings = mock(EmbeddingModel.class);
        when(embeddings.embed(anyString())).thenReturn(null);
        var props = new EmbeddingProperties("ollama", 1024,
                new EmbeddingProperties.Ollama("http://localhost:11434", "qwen3-embedding"));

        var validator = new EmbeddingDimensionValidator(embeddings, props);

        assertThatThrownBy(() -> validator.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null");
    }
}
