package io.veridex.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.shared.infrastructure.security.SecurityProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagementEndpointConfigurationTest {

    @Test
    void productionRejectsWildcardCorsAndInsecureCookie() {
        SecurityProperties insecureCors = new SecurityProperties("production", List.of("*"), true);
        assertThatThrownBy(insecureCors::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_security_configuration");

        SecurityProperties insecureCookie = new SecurityProperties("production", List.of("https://console.example.com"), false);
        assertThatThrownBy(insecureCookie::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_security_configuration");
    }

    @Test
    void requiredSecretRejectsBlankAndLocalDefaults() {
        assertThatThrownBy(() -> SecurityProperties.requiredSecret("db.password", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_security_configuration");
        assertThatThrownBy(() -> SecurityProperties.requiredSecret("db.password", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_security_configuration");
        assertThatThrownBy(() -> SecurityProperties.requiredSecret("db.password", "veridex-local"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_security_configuration");
        assertThatThrownBy(() -> SecurityProperties.requiredSecret("db.password", "veridex-local-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_security_configuration");
        assertThatThrownBy(() -> SecurityProperties.requiredSecret("db.password", "veridex"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_security_configuration");
    }

    @Test
    void requiredSecretAcceptsNonDefaultStrongValue() {
        assertThatCode(() -> SecurityProperties.requiredSecret("db.password", "strong-production-secret"))
                .doesNotThrowAnyException();
    }

    @Test
    void localEnvironmentAllowsPermissiveDefaults() {
        SecurityProperties local = new SecurityProperties("local", List.of("*"), false);
        assertThatCode(local::validateProduction).doesNotThrowAnyException();
    }

    @Test
    void productionEnvironmentAcceptsSecureConfiguration() {
        SecurityProperties secure = new SecurityProperties("production", List.of("https://console.example.com"), true);
        assertThatCode(secure::validateProduction).doesNotThrowAnyException();
    }
}
