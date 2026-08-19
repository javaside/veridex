package io.veridex.generation.infrastructure;

import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chat provider 配置装配入口：注册 {@link ChatProperties}，并在启动期校验 provider 合法值。
 * 非法 provider（如拼写错误或未实现的 provider 名）必须启动失败，不能静默降级或装配歧义（设计 D3/D10）。
 */
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatProviderConfiguration {

    @Bean
    ChatProviderVerifier chatProviderVerifier(ChatProperties properties) {
        return new ChatProviderVerifier(properties);
    }

    public static class ChatProviderVerifier implements InitializingBean {

        private static final Set<String> ALLOWED = Set.of("deterministic", "ollama");

        private final ChatProperties properties;

        public ChatProviderVerifier(ChatProperties properties) {
            this.properties = properties;
        }

        @Override
        public void afterPropertiesSet() {
            String provider = properties.provider();
            if (provider == null || !ALLOWED.contains(provider)) {
                throw new IllegalStateException("veridex.chat.provider must be one of " + ALLOWED
                        + " but was " + provider);
            }
        }
    }
}
