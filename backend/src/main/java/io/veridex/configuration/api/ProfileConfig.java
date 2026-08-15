package io.veridex.configuration.api;

public record ProfileConfig(ChunkingConfig chunking, RetrievalConfig retrieval,
                            GenerationConfig generation, PromptConfig prompt, ModelConfig model) {
}
