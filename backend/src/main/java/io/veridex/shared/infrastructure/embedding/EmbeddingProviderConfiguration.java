package io.veridex.shared.infrastructure.embedding;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding provider 配置装配入口：在启动期校验 provider 合法值。
 * 与 chat 侧的 {@code ChatProviderVerifier} 对称——非法 provider（拼写错误、未实现的值）
 * 必须启动失败并给出清晰错误，不能静默降级或装配歧义。
 */
@Configuration
public class EmbeddingProviderConfiguration {

    @Bean
    EmbeddingProviderVerifier embeddingProviderVerifier(EmbeddingProperties properties) {
        return new EmbeddingProviderVerifier(properties);
    }

    public static class EmbeddingProviderVerifier implements InitializingBean {

        private static final Set<String> ALLOWED = Set.of("deterministic", "ollama");

        private final EmbeddingProperties properties;

        public EmbeddingProviderVerifier(EmbeddingProperties properties) {
            this.properties = properties;
        }

        @Override
        public void afterPropertiesSet() {
            String provider = properties.provider();
            if (provider == null || !ALLOWED.contains(provider)) {
                throw new IllegalStateException("veridex.embedding.provider must be one of " + ALLOWED
                        + " but was " + provider);
            }
        }
    }
}
