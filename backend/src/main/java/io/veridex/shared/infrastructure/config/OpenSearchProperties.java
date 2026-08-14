package io.veridex.shared.infrastructure.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.search.opensearch")
public record OpenSearchProperties(
        List<String> uris,
        String indexPrefix) {
}
