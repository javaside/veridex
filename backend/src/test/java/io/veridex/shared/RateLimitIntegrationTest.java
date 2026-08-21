package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.VeridexApplication;
import io.veridex.iam.application.ApiKeyService;
import io.veridex.shared.infrastructure.RequestIds;
import io.veridex.support.PostgresIntegrationTest;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(classes = VeridexApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.jpa.hibernate.ddl-auto=validate", "veridex.api.rate-limit-per-minute=3",
                "veridex.chat.provider=deterministic"})
class RateLimitIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired ApiKeyService apiKeys;

    @Test
    void actuatorIsOnSeparateManagementPortAndNotRateLimitedByBusinessBucket() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        // 业务端口不再暴露 actuator 内容
        assertThat(send(client, "/actuator/health").statusCode()).isNotEqualTo(200);

        // 管理端口暴露 actuator，且不受业务限流桶影响
        for (int i = 0; i < 5; i++) {
            assertThat(managementGet(client, "/actuator/health").statusCode()).isEqualTo(200);
        }

        // 耗尽匿名业务桶后，管理端口仍正常
        for (int i = 0; i < 3; i++) {
            assertThat(send(client, options("/api/iam/keys")).statusCode()).isEqualTo(200);
        }
        assertThat(send(client, options("/api/iam/keys")).statusCode()).isEqualTo(429);
        assertThat(managementGet(client, "/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void sessionAndBearerApiKeyForSameUsernameShareOneBucket() throws Exception {
        String token = apiKeys.createFor(ADMIN, "PLATFORM_ADMIN", ADMIN, "rate-limit-test", List.of("qa")).token();
        HttpClient session = sessionClient();
        assertThat(session.send(post("/api/auth/login?username=admin&password=veridex"),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        assertThat(session.send(get("/api/qa/conversations"), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);
        assertThat(session.send(get("/api/qa/conversations"), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(200);

        assertThat(HttpClient.newHttpClient().send(bearerGet("/api/qa/conversations", token),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(HttpClient.newHttpClient().send(bearerGet("/api/qa/conversations", token),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
    }

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

    private HttpResponse<String> send(HttpClient client, String path) throws IOException, InterruptedException {
        return send(client, get(path));
    }

    private HttpResponse<String> managementGet(HttpClient client, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest options(String path) {
        return HttpRequest.newBuilder(URI.create(baseUri() + path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private HttpRequest bearerGet(String path, String token) {
        return HttpRequest.newBuilder(URI.create(baseUri() + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
    }

    private String baseUri() {
        return "http://localhost:" + port;
    }
}
