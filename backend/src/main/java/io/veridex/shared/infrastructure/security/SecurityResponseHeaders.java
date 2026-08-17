package io.veridex.shared.infrastructure.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * 统一配置浏览器安全响应头。默认的 {@code X-Content-Type-Options: nosniff}
 * 与 {@code X-Frame-Options: DENY} 由 Spring Security 自动提供。
 */
public final class SecurityResponseHeaders {

    private SecurityResponseHeaders() {
    }

    public static void configure(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; "
                                + "style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(
                        "camera=(), microphone=(), geolocation=(), payment=()")));
    }
}
