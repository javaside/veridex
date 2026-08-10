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
    void sensitiveAiObservationContentIsDisabledByDefault() {
        assertThat(environment.getProperty("spring.ai.chat.client.observations.log-prompt", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.chat.client.observations.log-completion", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.vectorstore.observations.log-query-response", Boolean.class)).isFalse();
    }
}
