package io.veridex.support;

import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.opensearch.testcontainers.OpenSearchContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class OpenSearchContainerConfiguration {

    @Bean(destroyMethod = "stop")
    OpenSearchContainer<?> openSearchContainer() {
        return new OpenSearchContainer<>(DockerImageName.parse("opensearchproject/opensearch:3.2.0"))
                .withEnv("DISABLE_SECURITY_PLUGIN", "true");
    }

    @Bean
    OpenSearchClient openSearchClient(OpenSearchContainer<?> openSearchContainer) {
        try {
            var restClient = org.opensearch.client.RestClient.builder(
                            org.apache.hc.core5.http.HttpHost.create(openSearchContainer.getHttpHostAddress()))
                    .build();
            return new OpenSearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("invalid container address", e);
        }
    }
}
