package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.generation.infrastructure.ChatProviderConfiguration;
import io.veridex.generation.infrastructure.DeepSeekChatConfiguration;
import io.veridex.generation.infrastructure.DeterministicChatModel;
import io.veridex.generation.infrastructure.OllamaChatConfiguration;
import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * provider 互斥装配（设计 D2/D3/D4）：deterministic 默认；ollama/deepseek 显式配置时只装配对应 ChatModel；
 * base URL 必须通过 OutboundAccessPolicy 校验（fail-fast，设计 §4.1）。
 */
class ChatProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeterministicChatModel.class, OllamaChatConfiguration.class,
                    DeepSeekChatConfiguration.class, ChatProviderConfiguration.class))
            .withPropertyValues(
                    "veridex.chat.provider=deterministic",
                    "veridex.chat.timeout=60s",
                    "veridex.chat.ollama.base-url=http://localhost:11434",
                    "veridex.chat.ollama.model=qwen3:8b",
                    "veridex.chat.deepseek.base-url=https://api.deepseek.com",
                    "veridex.chat.deepseek.api-key=sk-test",
                    "veridex.chat.deepseek.model=deepseek-v4-pro")
            .withBean(OutboundAccessPolicy.class,
                    () -> new OutboundAccessPolicy(Set.of("localhost", "api.deepseek.com"), Set.of(11434, 443), true));

    @Test
    void defaultProviderAssemblesOnlyDeterministicChatModel() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DeterministicChatModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(DeterministicChatModel.class);
        });
    }

    @Test
    void ollamaProviderAssemblesOnlyOllamaChatModel() {
        runner.withPropertyValues("veridex.chat.provider=ollama").run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).doesNotHaveBean(DeterministicChatModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(OllamaChatModel.class);
        });
    }

    @Test
    void deepseekProviderAssemblesOnlyDeepSeekChatModel() {
        runner.withPropertyValues("veridex.chat.provider=deepseek").run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).doesNotHaveBean(DeterministicChatModel.class);
            assertThat(context.getBean(ChatModel.class)).isInstanceOf(DeepSeekChatModel.class);
        });
    }

    @Test
    void ollamaBaseUrlMustPassOutboundPolicy() {
        runner.withPropertyValues("veridex.chat.provider=ollama",
                        "veridex.chat.ollama.base-url=http://not-allowed.example:11434")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void deepseekBaseUrlMustPassOutboundPolicy() {
        runner.withPropertyValues("veridex.chat.provider=deepseek",
                        "veridex.chat.deepseek.base-url=https://not-allowed.example")
                .run(context -> assertThat(context).hasFailed());
    }
}
