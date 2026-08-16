package io.veridex.trace.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
}
