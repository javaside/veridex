package io.veridex.configuration.domain;

public record ProfileConfig(ChunkingConfig chunking, RetrievalConfig retrieval,
                            GenerationConfig generation, PromptConfig prompt, ModelConfig model) {
}
