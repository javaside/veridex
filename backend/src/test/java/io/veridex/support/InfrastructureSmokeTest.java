package io.veridex.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class InfrastructureSmokeTest extends InfrastructureContainers {

    @Test
    void allRequiredDependenciesBecomeReachable() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(RABBITMQ.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();
        assertThat(MINIO.isRunning()).isTrue();
        assertThat(OPENSEARCH.isRunning()).isTrue();

        var response = openSearchHttpClient().send(
                openSearchHealthRequest(URI.create(OPENSEARCH.getHttpHostAddress())),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("status");
    }

    static HttpClient openSearchHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    static HttpRequest openSearchHealthRequest(URI baseUri) {
        return HttpRequest.newBuilder(baseUri.resolve("/_cluster/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
    }
}
