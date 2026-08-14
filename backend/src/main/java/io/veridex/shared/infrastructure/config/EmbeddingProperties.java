package io.veridex.shared.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.embedding")
public record EmbeddingProperties(
        String provider,
        int dimensions,
        Ollama ollama) {

    public record Ollama(String baseUrl, String model) {
    }
}
