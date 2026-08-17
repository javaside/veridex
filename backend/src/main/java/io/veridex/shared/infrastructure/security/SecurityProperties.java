package io.veridex.shared.infrastructure.security;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 安全基线配置。生产/试点环境（environment=production|pilot）启用严格校验；
 * local 环境允许宽松默认值，便于本地开发。
 */
@ConfigurationProperties(prefix = "veridex.security")
public record SecurityProperties(
        String environment,
        List<String> corsAllowedOrigins,
        boolean sessionCookieSecure) {

    private static final Set<String> PRODUCTION_ENVIRONMENTS = Set.of("production", "pilot");

    /** 任何生产 secret 都不得等于这些本地默认值。 */
    private static final Set<String> LOCAL_SECRET_DEFAULTS =
            Set.of("veridex-local", "veridex-local-secret", "veridex");

    public SecurityProperties {
        if (environment == null || environment.isBlank()) {
            environment = "local";
        }
        if (corsAllowedOrigins == null) {
            corsAllowedOrigins = List.of();
        }
    }

    public boolean isProduction() {
        return PRODUCTION_ENVIRONMENTS.contains(environment.toLowerCase(Locale.ROOT));
    }

    /** 生产环境拒绝通配符 CORS 和不安全的 Session cookie。 */
    public void validateProduction() {
        if (!isProduction()) {
            return;
        }
        if (corsAllowedOrigins.contains("*")) {
            throw new IllegalStateException("invalid_security_configuration");
        }
        if (!sessionCookieSecure) {
            throw new IllegalStateException("invalid_security_configuration");
        }
    }

    /** 拒绝空值或本地默认值；失败仅返回固定错误码，避免泄露具体配置项。 */
    public static void requiredSecret(String name, String value) {
        if (value == null || value.isBlank() || LOCAL_SECRET_DEFAULTS.contains(value)) {
            throw new IllegalStateException("invalid_security_configuration");
        }
    }
}
