package io.veridex.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.support.PostgresIntegrationTest;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class AuthFlowIntegrationTest extends PostgresIntegrationTest {

    @LocalServerPort int port;

    @Test
    void loginWithSeedUserEstablishesSessionAndMeReturnsProfile() throws Exception {
        HttpClient client = sessionClient();

        HttpResponse<String> login = client.send(post("/api/auth/login?username=admin&password=veridex"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"username\":\"admin\"");

        HttpResponse<String> me = client.send(get("/api/auth/me"), HttpResponse.BodyHandlers.ofString());
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"username\":\"admin\"");
    }

    @Test
    void anonymousBusinessRequestUsesLoginEntryPoint() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(get("/api/qa/conversations"), HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue(baseUri() + "/login");
    }

    @Test
    void unknownUserLoginIsRejected() throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient()
                .send(post("/api/auth/login?username=nobody&password=veridex"),
                        HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(401);
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
