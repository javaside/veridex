package io.veridex.generation.infrastructure;

import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import java.net.URI;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek Chat provider（真实云端模型，OpenAI 兼容协议）：仅 veridex.chat.provider=deepseek 时装配。
 * 与 Ollama 相同地手工组装、不引入 DeepSeek starter，避免额外自动配置与 Bean 歧义；
 * 装配前经 {@link OutboundAccessPolicy} 校验 base URL，fail-fast。
 */
@Configuration
@ConditionalOnProperty(name = "veridex.chat.provider", havingValue = "deepseek")
public class DeepSeekChatConfiguration {

    @Bean
    ChatModel deepSeekChatModel(ChatProperties properties, OutboundAccessPolicy outboundPolicy) {
        ChatProperties.DeepSeek deepseek = properties.deepseek();
        if (deepseek == null || deepseek.baseUrl() == null || deepseek.baseUrl().isBlank()) {
            throw new IllegalStateException("veridex.chat.deepseek.base-url is required when provider=deepseek");
        }
        if (deepseek.apiKey() == null || deepseek.apiKey().isBlank()) {
            throw new IllegalStateException("veridex.chat.deepseek.api-key is required when provider=deepseek");
        }
        outboundPolicy.validate(URI.create(deepseek.baseUrl()));
        var api = DeepSeekApi.builder()
                .baseUrl(deepseek.baseUrl())
                .apiKey(deepseek.apiKey())
                .build();
        var options = DeepSeekChatOptions.builder()
                .model(resolveModel(deepseek.model()))
                .build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(options)
                .build();
    }

    private static DeepSeekApi.ChatModel resolveModel(String model) {
        for (DeepSeekApi.ChatModel candidate : DeepSeekApi.ChatModel.values()) {
            if (candidate.getValue().equals(model)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown deepseek model: " + model);
    }
}
