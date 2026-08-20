package io.veridex.generation.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat 生成配置（设计 §4.1）：provider 决定装配 deterministic / ollama / deepseek。
 * provider 只允许 deterministic/ollama/deepseek，非法值在启动期失败（见 ChatProviderVerifier）。
 */
@ConfigurationProperties(prefix = "veridex.chat")
public record ChatProperties(String provider, Duration timeout, Ollama ollama, DeepSeek deepseek) {

    public record Ollama(String baseUrl, String model) {
    }

    public record DeepSeek(String baseUrl, String apiKey, String model) {
    }
}
