package io.veridex.trace.infrastructure;

import io.veridex.trace.application.TraceBodyAccessAuditor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TraceBodyProperties.class)
public class TraceBodyConfiguration {

    @Bean
    TraceBodyKeyRing traceBodyKeyRing(TraceBodyProperties properties) {
        return new TraceBodyKeyRing(properties);
    }

    @Bean
    TraceBodyCrypto traceBodyCrypto(TraceBodyKeyRing keyRing) {
        return new TraceBodyCrypto(keyRing);
    }

    @Bean
    FilterRegistrationBean<TraceBodyDeniedAccessFilter> traceBodyDeniedAccessFilter(TraceBodyAccessAuditor auditor) {
        FilterRegistrationBean<TraceBodyDeniedAccessFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceBodyDeniedAccessFilter(auditor));
        registration.addUrlPatterns("/*");
        registration.setOrder(Integer.MIN_VALUE + 100);
        return registration;
    }
}
