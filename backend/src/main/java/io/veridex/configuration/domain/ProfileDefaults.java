package io.veridex.configuration.domain;

import io.veridex.configuration.api.ChunkingConfig;
import io.veridex.configuration.api.GenerationConfig;
import io.veridex.configuration.api.ModelConfig;
import io.veridex.configuration.api.ProfileConfig;
import io.veridex.configuration.api.PromptConfig;
import io.veridex.configuration.api.RetrievalConfig;

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
