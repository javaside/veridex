package io.veridex.generation.infrastructure;

import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import java.net.URI;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 真实 Chat provider（设计 D4/D12）：仅 veridex.chat.provider=ollama 时装配。
 * 手工组装、不引入 Ollama starter，避免额外自动配置与 Bean 歧义（设计 §4.1）；
 * 装配前经 {@link OutboundAccessPolicy} 校验，fail-fast。
 */
@Configuration
@ConditionalOnProperty(name = "veridex.chat.provider", havingValue = "ollama")
public class OllamaChatConfiguration {

    @Bean
    ChatModel ollamaChatModel(ChatProperties properties, OutboundAccessPolicy outboundPolicy) {
        outboundPolicy.validate(URI.create(properties.ollama().baseUrl()));
        var api = OllamaApi.builder()
                .baseUrl(properties.ollama().baseUrl())
                .build();
        var options = OllamaChatOptions.builder()
                .model(properties.ollama().model())
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(options)
                .build();
    }
}
