package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class ObservabilityEndpointIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    RestTestClient rest;

    @Test
    void prometheusEndpointContainsFrameworkMetricsWithoutAiContentMetrics() {
        String body = rest.get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith("text/plain")
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body)
                .contains("jvm_")
                .contains("process_uptime")
                .doesNotContain("prompt")
                .doesNotContain("completion");
    }
}
