package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.config.InfrastructurePropertiesConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

// 只加载最小配置，检查 application.yml 的安全默认值
@SpringBootTest(classes = InfrastructurePropertiesConfiguration.class)
class ConfigurationSafetyTest {

    @Autowired
    Environment environment;

    @Test
    void observabilityUsesSafeDefaults() {
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.1");
        assertThat(environment.getProperty("veridex.trace.body.capture-policy")).isEqualTo("NONE");
        assertThat(environment.getProperty("veridex.trace.body.retention")).isEqualTo("24h");
        assertThat(environment.getProperty("veridex.trace.body.max-plaintext-size")).isEqualTo("256KiB");
        assertThat(environment.getProperty("veridex.trace.body.cleanup-interval")).isEqualTo("1h");
        assertThat(environment.getProperty("veridex.trace.body.cleanup-batch-size")).isEqualTo("500");
        assertThat(environment.getProperty("veridex.trace.body.fingerprint-key")).isEmpty();
        assertThat(environment.getProperty("veridex.trace.body.current-key-id")).isEmpty();
        assertThat(environment.getProperty("veridex.trace.body.current-key")).isEmpty();
        assertThat(environment.getProperty("veridex.trace.body.historical-keys")).isEmpty();
        assertThat(environment.getProperty("spring.ai.chat.client.observations.log-prompt", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.chat.client.observations.log-completion", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.vectorstore.observations.log-query-response", Boolean.class)).isFalse();
    }
}
