package io.veridex.generation.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chat 生成配置（设计 §4.1）：provider 决定装配 deterministic 还是 Ollama。
 * provider 只允许 deterministic/ollama，非法值在启动期失败（见 ChatProviderVerifier）。
 */
@ConfigurationProperties(prefix = "veridex.chat")
public record ChatProperties(String provider, Duration timeout, Ollama ollama) {

    public record Ollama(String baseUrl, String model) {
    }
}
