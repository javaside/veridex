package io.veridex;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class OpenApiDocsTest extends PostgresIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired RestTestClient rest;
    @Autowired ApiKeyService apiKeys;

    @BeforeEach
    void clearSession() {
        rest.post().uri("/api/auth/logout").exchange().expectStatus().isNoContent();
    }

    @Test
    void adminSessionSeesOpenApiJsonWithApiPaths() {
        login("admin");

        rest.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isNotEmpty()
                .jsonPath("$.paths./api/qa/ask").exists()
                .jsonPath("$.paths./api/iam/keys").exists();
    }

    @Test
    void anonymousIsRejected() {
        rest.get().uri("/v3/api-docs").exchange().expectStatus().is4xxClientError();
    }

    @Test
    void employeeSessionIsForbidden() {
        login("employee");

        rest.get().uri("/v3/api-docs").exchange().expectStatus().isForbidden();
    }

    @Test
    void adminApiKeyIsForbiddenWithInsufficientScope() {
        String token = apiKeys.createFor(ADMIN, "PLATFORM_ADMIN", ADMIN, "OpenAPI test", List.of("qa")).token();

        rest.get().uri("/v3/api-docs")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .json("{\"error\":\"insufficient_scope\"}", JsonCompareMode.STRICT);
    }

    private void login(String username) {
        rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange()
                .expectStatus().isOk();
    }
}
