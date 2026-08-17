package io.veridex.shared.infrastructure.security;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 出站访问 allowlist。空列表意味着没有可用出站目标（fail closed）。
 */
@ConfigurationProperties(prefix = "veridex.security.outbound")
public record OutboundSecurityProperties(
        Set<String> allowedHosts,
        Set<Integer> allowedPorts,
        boolean allowInsecureHttp) {

    public OutboundSecurityProperties {
        if (allowedHosts == null) {
            allowedHosts = Set.of();
        }
        if (allowedPorts == null) {
            allowedPorts = Set.of();
        }
    }
}
