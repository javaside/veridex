package io.veridex.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.client.RestTestClient;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureRestTestClient
class ApiKeyIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KADMIN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final JsonMapper JSON = new JsonMapper();

    @Autowired RestTestClient rest;
    @Autowired ApiKeyService apiKeys;

    private String csrfToken;

    @BeforeEach
    void clearSession() {
        rest.post().uri("/api/auth/logout").exchange().expectStatus().isNoContent();
        csrfToken = null;
    }

    private void login(String username) throws Exception {
        var result = rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo(username)
                .returnResult();
        csrfToken = extractCsrfToken(result.getResponseHeaders().get(HttpHeaders.SET_COOKIE));
        if (csrfToken == null || csrfToken.isEmpty()) {
            byte[] body = rest.get().uri("/api/auth/csrf")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .returnResult()
                    .getResponseBody();
            csrfToken = JSON.readTree(body).path("token").asText();
        }
    }

    private static String extractCsrfToken(List<String> cookies) {
        if (cookies == null) {
            return null;
        }
        return cookies.stream()
                .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .map(cookie -> {
                    int end = cookie.indexOf(';');
                    return cookie.substring("XSRF-TOKEN=".length(), end < 0 ? cookie.length() : end);
                })
                .findFirst()
                .orElse(null);
    }

    @Test
    void adminCreatesListsAndRevokesKeys() throws Exception {
        login("admin");

        rest.post().uri("/api/iam/keys")
                .header("X-XSRF-TOKEN", csrfToken)
                .body(new io.veridex.iam.api.CreateKeyRequest("集成用", null, List.of("qa")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.token").value(v -> assertThat((String) v).startsWith("vd_"))
                .jsonPath("$.scopes[0]").isEqualTo("qa");

        rest.get().uri("/api/iam/keys")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].tokenPrefix").isNotEmpty()
                .jsonPath("$[0].token").doesNotExist();

        var keys = apiKeys.listFor(ADMIN, "PLATFORM_ADMIN");
        assertThat(keys).isNotEmpty();
        String id = keys.get(0).id().toString();

        rest.delete().uri("/api/iam/keys/" + id)
                .header("X-XSRF-TOKEN", csrfToken)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        rest.get().uri("/api/iam/keys")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].revokedAt").isNotEmpty();
    }

    @Test
    void platformAdminListsAllKeysWhileKnowledgeAdminListsOnlyOwnKeys() throws Exception {
        login("admin");
        rest.post().uri("/api/iam/keys")
                .header("X-XSRF-TOKEN", csrfToken)
                .body(new io.veridex.iam.api.CreateKeyRequest("admin-own", null, List.of("qa")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(ADMIN.toString());
        rest.post().uri("/api/iam/keys")
                .header("X-XSRF-TOKEN", csrfToken)
                .body(new io.veridex.iam.api.CreateKeyRequest("delegated", KADMIN.toString(), List.of("feedback")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(KADMIN.toString());

        rest.get().uri("/api/iam/keys")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].name").value(values -> assertThat(values.toString())
                        .contains("admin-own", "delegated"));

        login("kadmin");
        rest.get().uri("/api/iam/keys")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].name").value(values -> assertThat(values.toString())
                        .contains("delegated")
                        .doesNotContain("admin-own"))
                .jsonPath("$[*].userId").value(values -> assertThat((List<?>) values)
                        .allMatch(KADMIN.toString()::equals));
        rest.post().uri("/api/iam/keys")
                .header("X-XSRF-TOKEN", csrfToken)
                .body(new io.veridex.iam.api.CreateKeyRequest("forbidden-delegation", ADMIN.toString(), List.of("qa")))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("targetUserId must match actor unless caller is PLATFORM_ADMIN");
    }

    @Test
    void platformAdminCannotCreateKeyForUnknownTargetUser() throws Exception {
        login("admin");
        UUID unknownUser = UUID.randomUUID();
        String keyName = "unknown-owner-" + UUID.randomUUID();

        rest.post().uri("/api/iam/keys")
                .header("X-XSRF-TOKEN", csrfToken)
                .body(new io.veridex.iam.api.CreateKeyRequest(keyName, unknownUser.toString(), List.of("qa")))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.detail").isEqualTo("target user not found: " + unknownUser);

        rest.get().uri("/api/iam/keys")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].name").value(values -> assertThat(values.toString()).doesNotContain(keyName));
    }

    @Test
    void createRejectsNonCanonicalScopeNames() throws Exception {
        login("admin");

        for (String invalid : List.of("QA", "KNOWLEDGE_READ", "Knowledge:Read")) {
            rest.post().uri("/api/iam/keys")
                    .header("X-XSRF-TOKEN", csrfToken)
                    .body(new io.veridex.iam.api.CreateKeyRequest("x", null, List.of(invalid)))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(400);
        }
    }

    @Test
    void employeeIsForbiddenFromKeyManagement() throws Exception {
        login("employee");
        rest.get().uri("/api/iam/keys")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("admin role required");
        rest.post().uri("/api/iam/keys")
                .header("X-XSRF-TOKEN", csrfToken)
                .body(new io.veridex.iam.api.CreateKeyRequest("x", null, List.of("qa")))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.detail").isEqualTo("admin role required");
    }
}
