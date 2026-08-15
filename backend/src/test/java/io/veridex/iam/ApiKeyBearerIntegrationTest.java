package io.veridex.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Bearer token 认证 + scope 鉴权（Task 3）。
 * scope 契约见 ApiKeyScope；key 无法访问 key 管理与 docs。
 */
@AutoConfigureRestTestClient
class ApiKeyBearerIntegrationTest extends PostgresIntegrationTest {

    private static final java.util.UUID ADMIN =
            java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired RestTestClient rest;
    @Autowired ApiKeyService apiKeys;

    @BeforeEach
    void clearSession() {
        rest.post().uri("/api/auth/logout").exchange().expectStatus().isNoContent();
    }

    private String issueKey(List<String> scopes) {
        return apiKeys.createFor(ADMIN, "PLATFORM_ADMIN", ADMIN, "集成", scopes).token();
    }

    @Test
    void bearerKeyHitsQaEndpoint() {
        // qa scope 允许 /api/qa/conversations；无 session，仅 Bearer
        String token = issueKey(List.of("qa"));

        rest.get().uri("/api/qa/conversations")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void bearerKeyWithoutMatchingScopeIsForbidden() {
        String token = issueKey(List.of("feedback"));

        rest.get().uri("/api/qa/conversations")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void keyCannotAccessForbiddenOrUnmappedNamespaces() {
        String token = issueKey(List.of("qa", "knowledge:read"));

        expectForbidden(token, "/api/iam/keys");
        expectForbidden(token, "/v3/api-docs");
        expectForbidden(token, "/actuator/health");
        expectForbidden(token, "/api/auth/login");
        expectForbidden(token, "/api/unmapped");
    }

    @Test
    void revokedOrInvalidKeyIsUnauthorizedWithJsonBody() {
        String token = issueKey(List.of("qa"));
        var view = apiKeys.listFor(ADMIN, "PLATFORM_ADMIN").get(0);
        apiKeys.revoke(ADMIN, "PLATFORM_ADMIN", view.id());

        expectInvalidApiKey(token);
        expectInvalidApiKey("vd_invalidinvalidinvalidinvalidinvalidinvalid33");
    }

    @Test
    void sessionAuthWinsEvenWhenInvalidBearerKeyIsPresent() {
        rest.post().uri("/api/auth/login?username=admin&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("admin");

        rest.get().uri("/api/iam/keys")
                .headers(h -> h.setBearerAuth("vd_invalidinvalidinvalidinvalidinvalidinvalid33"))
                .exchange()
                .expectStatus().isOk();

        assertThat(apiKeys.listFor(ADMIN, "PLATFORM_ADMIN")).isNotEmpty();
    }

    private void expectForbidden(String token, String path) {
        rest.get().uri(path)
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").isEqualTo(403);
    }

    private void expectInvalidApiKey(String token) {
        rest.get().uri("/api/qa/conversations")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("invalid_api_key");
    }
}
