package io.veridex.knowledge.infrastructure.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(UploadSecurityProperties.class)
public class UploadSecurityConfiguration {

    @Bean
    UploadContentInspector uploadContentInspector(UploadSecurityProperties properties) {
        return new UploadContentInspector(properties);
    }
}
