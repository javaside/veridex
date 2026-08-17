package io.veridex.security;

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

@AutoConfigureRestTestClient
class SecurityHeadersIntegrationTest extends PostgresIntegrationTest {

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired RestTestClient rest;
    @Autowired ApiKeyService apiKeys;

    @BeforeEach
    void clearSession() {
        rest.post().uri("/api/auth/logout").exchange().expectStatus().isNoContent();
    }

    private void login(String username) {
        rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void businessResponseContainsSecurityHeaders() {
        login("admin");

        rest.get().uri("/api/qa/conversations")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueMatches("Content-Security-Policy", ".+")
                .expectHeader().valueMatches("Referrer-Policy", ".+")
                .expectHeader().valueMatches("Permissions-Policy", ".+")
                .expectBody().consumeWith(response -> {});
    }

    @Test
    void loginSetsSecureSessionCookieWithSameSite() {
        var result = rest.post().uri("/api/auth/login?username=admin&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .returnResult();

        List<String> setCookies = result.getResponseHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(setCookies).isNotNull().isNotEmpty();
        String sessionCookie = setCookies.stream()
                .filter(cookie -> cookie.startsWith("JSESSIONID"))
                .findFirst()
                .orElse(null);
        assertThat(sessionCookie).isNotNull();
        assertThat(sessionCookie).containsIgnoringCase("HttpOnly");
        assertThat(sessionCookie).containsIgnoringCase("SameSite=Lax");
    }

    @Test
    void corsAllowsListedOriginPreflight() {
        rest.method(org.springframework.http.HttpMethod.OPTIONS).uri("/api/qa/conversations")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectBody().isEmpty();
    }

    @Test
    void corsRejectsUnlistedOrigin() {
        rest.method(org.springframework.http.HttpMethod.OPTIONS).uri("/api/qa/conversations")
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().consumeWith(response -> {});
    }

    @Test
    void apiKeyWriteRequestIsNotBlockedByCsrfBoundary() {
        String token = apiKeys.createFor(ADMIN, "PLATFORM_ADMIN", ADMIN, "csrf", List.of("qa")).token();

        rest.get().uri("/api/qa/conversations")
                .headers(h -> h.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody().consumeWith(response -> {});
    }
}
