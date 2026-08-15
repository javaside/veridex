package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureRestTestClient
class OpenApiDocsTest extends PostgresIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired RestTestClient rest;
    @Autowired ApiKeyService apiKeys;
    @Autowired JsonMapper jsonMapper;

    @BeforeEach
    void clearSession() {
        rest.post().uri("/api/auth/logout")
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
    }

    @Test
    void adminSessionSeesOpenApiJsonWithOnlyApiPaths() {
        login("admin");

        var response = rest.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody(String.class)
                .returnResult();
        var document = jsonMapper.readTree(response.getResponseBody());
        var paths = document.get("paths");

        assertThat(document.get("openapi").asText()).startsWith("3.");
        assertThat(paths.propertyNames()).allMatch(path -> path.startsWith("/api/"));
        assertThat(paths.get("/api/qa/ask")).isNotNull();
        assertThat(paths.get("/api/iam/keys")).isNotNull();
    }

    @Test
    void anonymousIsUnauthorized() {
        rest.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().consumeWith(response -> {});
    }

    @Test
    void employeeSessionIsForbidden() {
        login("employee");

        rest.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().consumeWith(response -> {});
    }

    @Test
    void adminSessionSeesOpenApiYaml() {
        login("admin");

        rest.get().uri("/v3/api-docs.yaml")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/vnd.oai.openapi")
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("openapi: 3.")
                        .contains("/api/qa/ask:"));
    }

    @Test
    void employeeSessionIsForbiddenFromOpenApiYaml() {
        login("employee");

        rest.get().uri("/v3/api-docs.yaml")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().consumeWith(response -> {});
    }

    @Test
    void adminApiKeyIsForbiddenWithInsufficientScope() {
        String token = apiKeys.createFor(ADMIN, "PLATFORM_ADMIN", ADMIN, "OpenAPI test", List.of("qa")).token();

        assertApiKeyCannotAccessDocs(token, "/v3/api-docs");
        assertApiKeyCannotAccessDocs(token, "/v3/api-docs.yaml");
    }

    private void assertApiKeyCannotAccessDocs(String token, String path) {
        rest.get().uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .json("{\"error\":\"insufficient_scope\"}", JsonCompareMode.STRICT);
    }

    private void login(String username) {
        rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo(username);
    }
}
