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

    @Autowired RestTestClient rest;
    @Autowired ApiKeyService apiKeys;

    private void login(String username) {
        rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange().expectStatus().isOk();
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
                .jsonPath("$.scopes[0]").isEqualTo("QA");

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
                .exchange().expectStatus().isNoContent();

        rest.get().uri("/api/iam/keys")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].revokedAt").isNotEmpty();
    }

    @Test
    void employeeIsForbiddenFromKeyManagement() {
        login("employee");
        rest.get().uri("/api/iam/keys").exchange().expectStatus().isForbidden();
        rest.post().uri("/api/iam/keys")
                .body(new io.veridex.iam.api.CreateKeyRequest("x", null, List.of("qa")))
                .exchange().expectStatus().isForbidden();
    }
}
