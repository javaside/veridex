package io.veridex.ingestion.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ParserExecutionProperties.class)
public class ParserExecutionConfiguration {

    @Bean
    ParserExecutionGuard parserExecutionGuard(ParserExecutionProperties properties) {
        return new ParserExecutionGuard(properties.timeout(), properties.tempRoot());
    }
}
