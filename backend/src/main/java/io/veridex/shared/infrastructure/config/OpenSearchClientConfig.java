package io.veridex.shared.infrastructure.config;

import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenSearchClientConfig {

    @Bean
    @ConditionalOnMissingBean
    OpenSearchClient openSearchClient(OpenSearchProperties properties) {
        var hosts = properties.uris().stream()
                .map(uri -> {
                    try {
                        return HttpHost.create(uri);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("invalid OpenSearch URI " + uri, e);
                    }
                })
                .toArray(HttpHost[]::new);
        var restClient = org.opensearch.client.RestClient.builder(hosts).build();
        return new OpenSearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}
