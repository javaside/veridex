package io.veridex.shared.infrastructure.embedding;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "veridex.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingConfiguration {

    @Bean
    EmbeddingModel ollamaEmbeddingModel(EmbeddingProperties properties) {
        var api = OllamaApi.builder()
                .baseUrl(properties.ollama().baseUrl())
                .build();
        var options = OllamaEmbeddingOptions.builder()
                .model(properties.ollama().model())
                .build();
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(options)
                .build();
    }
}
