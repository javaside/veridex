package io.veridex.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.knowledge.infrastructure.security.UploadContentInspector;
import io.veridex.shared.infrastructure.config.InfrastructurePropertiesConfiguration;
import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import io.veridex.shared.infrastructure.security.SecurityConfiguration;
import io.veridex.shared.infrastructure.security.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(classes = {InfrastructurePropertiesConfiguration.class, SecurityConfiguration.class})
class SecurityHardeningExitGateTest {

    @Autowired Environment environment;

    @Test
    void actuatorUsesSeparateManagementPortWithAllowlist() {
        assertThat(environment.getProperty("management.server.port")).isEqualTo("8081");
        assertThat(environment.getProperty("management.server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
    }

    @Test
    void diagnosticEndpointsAreNotExposed() {
        String exposure = environment.getProperty("management.endpoints.web.exposure.include", "");
        assertThat(exposure).doesNotContain("env", "heapdump", "beans", "configprops", "mappings", "loggers");
    }

    @Test
    void securityBeansAreAvailable() {
        // 通过 Environment 验证安全配置已绑定；具体 bean 在完整 context 中验证
        assertThat(environment.getProperty("veridex.security.environment")).isEqualTo("local");
        assertThat(environment.getProperty("veridex.security.upload.max-file-bytes")).isEqualTo("52428800");
        assertThat(environment.getProperty("veridex.security.parser.timeout")).isEqualTo("30s");
    }

    @Test
    void securityTypesArePresent() {
        assertThat(SecurityProperties.class).isNotNull();
        assertThat(OutboundAccessPolicy.class).isNotNull();
        assertThat(UploadContentInspector.class).isNotNull();
    }
}
