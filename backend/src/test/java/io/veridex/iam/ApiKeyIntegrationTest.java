package io.veridex.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class ApiKeyIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID KADMIN = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired RestTestClient rest;
    @Autowired ApiKeyService apiKeys;

    private void login(String username) {
        rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo(username);
    }

    @Test
    void adminCreatesListsAndRevokesKeys() {
        login("admin");

        rest.post().uri("/api/iam/keys")
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
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        rest.get().uri("/api/iam/keys")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].revokedAt").isNotEmpty();
    }

    @Test
    void platformAdminListsAllKeysWhileKnowledgeAdminListsOnlyOwnKeys() {
        login("admin");
        rest.post().uri("/api/iam/keys")
                .body(new io.veridex.iam.api.CreateKeyRequest("admin-own", null, List.of("qa")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(ADMIN.toString());
        rest.post().uri("/api/iam/keys")
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
                .body(new io.veridex.iam.api.CreateKeyRequest("forbidden-delegation", ADMIN.toString(), List.of("qa")))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").isEqualTo(403);
    }

    @Test
    void createRejectsNonCanonicalScopeNames() {
        login("admin");

        for (String invalid : List.of("QA", "KNOWLEDGE_READ", "Knowledge:Read")) {
            rest.post().uri("/api/iam/keys")
                    .body(new io.veridex.iam.api.CreateKeyRequest("x", null, List.of(invalid)))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(400);
        }
    }

    @Test
    void employeeIsForbiddenFromKeyManagement() {
        login("employee");
        rest.get().uri("/api/iam/keys")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").isEqualTo(403);
        rest.post().uri("/api/iam/keys")
                .body(new io.veridex.iam.api.CreateKeyRequest("x", null, List.of("qa")))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.status").isEqualTo(403);
    }
}
