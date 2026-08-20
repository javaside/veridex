package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.generation.infrastructure.ChatProperties;
import io.veridex.generation.infrastructure.ChatProviderConfiguration.ChatProviderVerifier;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * provider 合法值校验（设计 D3/D10）：只允许 deterministic/ollama/deepseek，非法值启动失败。
 */
class ChatProviderVerifierTest {

    private static ChatProviderVerifier verifier(String provider) {
        return new ChatProviderVerifier(new ChatProperties(provider, Duration.ofSeconds(60),
                new ChatProperties.Ollama("http://localhost:11434", "qwen3:8b"),
                new ChatProperties.DeepSeek("https://api.deepseek.com", "sk-test", "deepseek-v4-pro")));
    }

    @Test
    void acceptsDeterministicOllamaAndDeepseek() {
        assertThatCode(() -> verifier("deterministic").afterPropertiesSet()).doesNotThrowAnyException();
        assertThatCode(() -> verifier("ollama").afterPropertiesSet()).doesNotThrowAnyException();
        assertThatCode(() -> verifier("deepseek").afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownProvider() {
        assertThatThrownBy(() -> verifier("gpt-4").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt-4");
    }

    @Test
    void rejectsNullProvider() {
        assertThatThrownBy(() -> verifier(null).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }
}
