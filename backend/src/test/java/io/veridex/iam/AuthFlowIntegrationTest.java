package io.veridex.iam;

import io.veridex.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
class AuthFlowIntegrationTest extends PostgresIntegrationTest {

    @Autowired RestTestClient rest;

    @BeforeEach
    void clearSession() {
        rest.post().uri("/api/auth/logout").exchange().expectStatus().isNoContent();
    }

    @Test
    void loginWithSeedUserEstablishesSessionAndMeReturnsProfile() {
        rest.post().uri("/api/auth/login?username=admin&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("admin");

        // RestTestClient 自动保持 session：第二次请求携带 JSESSIONID
        rest.get().uri("/api/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("admin");
    }

    @Test
    void unknownUserLoginIsRejected() {
        rest.post().uri("/api/auth/login?username=nobody&password=veridex")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
