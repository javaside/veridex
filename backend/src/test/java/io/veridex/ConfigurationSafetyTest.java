package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
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
