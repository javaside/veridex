package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.VeridexApplication;
import io.veridex.shared.infrastructure.RequestIds;
import io.veridex.support.PostgresIntegrationTest;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(classes = VeridexApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "veridex.api.rate-limit-per-minute=3"})
class RateLimitIntegrationTest extends PostgresIntegrationTest {

    @LocalServerPort int port;

    @Test
    void fourthAuthenticatedRequestWithinMinuteGetsRateLimitResponse() throws Exception {
        HttpClient client = sessionClient();
        HttpResponse<String> login = client.send(
                post("/api/auth/login?username=employee&password=veridex"), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);

        for (int i = 0; i < 3; i++) {
            assertThat(client.send(get("/api/qa/conversations"), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(200);
        }

        HttpResponse<String> blocked = client.send(
                get("/api/qa/conversations"), HttpResponse.BodyHandlers.ofString());
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After"))
                .hasValueSatisfying(value -> assertThat(value).matches("[1-9]|[1-5][0-9]|60"));
        assertThat(blocked.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        assertThat(blocked.headers().firstValue(RequestIds.HEADER)).isPresent();
        assertThat(blocked.body()).isEqualTo("{\"error\":\"rate_limited\"}");
    }

    private HttpClient sessionClient() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private HttpRequest post(String path) {
        return HttpRequest.newBuilder(URI.create(baseUri() + path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(baseUri() + path)).GET().build();
    }

    private String baseUri() {
        return "http://localhost:" + port;
    }
}
