package io.veridex.shared.infrastructure.embedding;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingConfiguration.class);

    @Bean
    EmbeddingModel ollamaEmbeddingModel(EmbeddingProperties properties, OutboundAccessPolicy outboundPolicy) {
        outboundPolicy.validate(URI.create(properties.ollama().baseUrl()));
        var api = OllamaApi.builder()
                .baseUrl(properties.ollama().baseUrl())
                .build();
        var options = OllamaEmbeddingOptions.builder()
                .model(properties.ollama().model())
                .build();
        log.info("veridex.embedding.provider=ollama (model={}, baseUrl={})",
                properties.ollama().model(), properties.ollama().baseUrl());
        return OllamaEmbeddingModel.builder()
                .ollamaApi(api)
                .options(options)
                .build();
    }
}
