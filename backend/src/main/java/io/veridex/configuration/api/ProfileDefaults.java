package io.veridex.configuration.api;

public final class ProfileDefaults {

    public static final String SYSTEM_TEMPLATE =
            "你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n";

    private ProfileDefaults() {
    }

    public static ProfileConfig defaults() {
        return new ProfileConfig(
                new ChunkingConfig(2000, 80),
                new RetrievalConfig(30, 60, 6, 3, 4000),
                new GenerationConfig(6, 50),
                new PromptConfig(SYSTEM_TEMPLATE),
                new ModelConfig("deterministic", "deterministic"));
    }
}
