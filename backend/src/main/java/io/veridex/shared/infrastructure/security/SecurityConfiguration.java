package io.veridex.shared.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({SecurityProperties.class, OutboundSecurityProperties.class})
public class SecurityConfiguration {

    @Bean
    OutboundAccessPolicy outboundAccessPolicy(OutboundSecurityProperties properties) {
        return new OutboundAccessPolicy(properties.allowedHosts(), properties.allowedPorts(),
                properties.allowInsecureHttp());
    }

    @Bean
    SafeHttpClient safeHttpClient(OutboundAccessPolicy policy) {
        return new SafeHttpClient(policy);
    }
}
