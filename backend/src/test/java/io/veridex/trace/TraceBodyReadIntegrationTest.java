package io.veridex.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.support.PostgresIntegrationTest;
import io.veridex.trace.infrastructure.TraceBodyCrypto;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

@AutoConfigureRestTestClient
@TestPropertySource(properties = {
        "veridex.trace.body.capture-policy=ALL",
        "veridex.trace.body.current-key-id=test-key",
        "veridex.trace.body.current-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "veridex.trace.body.historical-keys=test-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class TraceBodyReadIntegrationTest extends PostgresIntegrationTest {

    private static final UUID RUN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String PLAINTEXT = "secret-body-never-audit";

    @Autowired RestTestClient rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApiKeyService apiKeys;
    @Autowired TraceBodyCrypto crypto;

    @BeforeEach
    void reset() {
        rest.post().uri("/api/auth/logout").exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
        jdbc.update("DELETE FROM audit_event WHERE resource_type = 'trace_body'");
        jdbc.update("DELETE FROM trace_body WHERE query_run_id = ?", RUN_ID);
        jdbc.update("DELETE FROM query_run WHERE id = ?", RUN_ID);
    }

    @Test
    void platformAdminGetsDecryptedEnvelopeAndNoStore() {
        insertBody(Instant.now().plusSeconds(3600), envelope(PLAINTEXT));
        login("admin");

        rest.get().uri("/api/traces/{id}/body", RUN_ID)
                .header("X-Trace-Access-Reason", "Incident #42 / review")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .jsonPath("$.runId").isEqualTo(RUN_ID.toString())
                .jsonPath("$.createdAt").isNotEmpty()
                .jsonPath("$.expiresAt").isNotEmpty()
                .jsonPath("$.envelope.material").isEqualTo(PLAINTEXT);

        assertSingleAudit("trace.body.read", null, true);
    }

    @Test
    void missingAndInvalidReasonsAreRejectedWithoutPersistingRawInput() {
        login("admin");

        rest.get().uri("/api/traces/{id}/body", RUN_ID)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().consumeWith(response -> assertNoLeak(response.getResponseBody()));
        assertSingleAudit("trace.body.read_denied", null, false);

        clearAudits();
        String invalidReason = "review!";
        rest.get().uri("/api/traces/{id}/body", RUN_ID)
                .header("X-Trace-Access-Reason", invalidReason)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().consumeWith(response -> assertNoLeak(response.getResponseBody()));
        assertSingleAudit("trace.body.read_denied", invalidReason, false);
    }

    @Test
    void missingAndExpiredBodiesAreNotFoundAndExpiredRowIsDeleted() {
        login("admin");

        rest.get().uri("/api/traces/{id}/body", RUN_ID)
                .header("X-Trace-Access-Reason", "review")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().consumeWith(response -> assertNoLeak(response.getResponseBody()));
        assertSingleAudit("trace.body.not_found", null, false);

        clearAudits();
        insertBody(Instant.now().minusSeconds(1), envelope(PLAINTEXT));
        rest.get().uri("/api/traces/{id}/body", RUN_ID)
                .header("X-Trace-Access-Reason", "review")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().consumeWith(response -> assertNoLeak(response.getResponseBody()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trace_body WHERE query_run_id = ?", Integer.class, RUN_ID))
                .isZero();
        assertSingleAudit("trace.body.not_found", null, false);
    }

    @Test
    void tamperedAndMissingKeyBodiesReturnGenericServerErrors() {
        insertBody(Instant.now().plusSeconds(3600), envelope(PLAINTEXT));
        jdbc.update("UPDATE trace_body SET encrypted_body = decode('00', 'hex') WHERE query_run_id = ?", RUN_ID);
        login("admin");

        expectGenericDecryptFailure();
        assertSingleAudit("trace.body.decrypt_failed", null, false);

        clearAudits();
        jdbc.update("UPDATE trace_body SET encryption_key_id = 'retired-secret-key' WHERE query_run_id = ?", RUN_ID);
        expectGenericDecryptFailure();
        assertSingleAudit("trace.body.decrypt_failed", "retired-secret-key", false);
    }

    @Test
    void knowledgeAdminEmployeeAndApiKeyAreForbiddenAndAudited() {
        login("kadmin");
        expectForbidden(null);
        assertSingleAudit("trace.body.read_denied", null, false);

        clearAudits();
        login("employee");
        expectForbidden(null);
        assertSingleAudit("trace.body.read_denied", null, false);

        clearAudits();
        logout();
        String token = apiKeys.createFor(ADMIN, "PLATFORM_ADMIN", ADMIN, "test", List.of("qa")).token();
        expectForbidden(token);
        assertSingleAudit("trace.body.read_denied", token, false);
    }

    @Test
    void anonymousUsesExistingEntryPointAndIsAudited() {
        rest.get().uri("/api/traces/{id}/body", RUN_ID)
                .header("X-Trace-Access-Reason", "review")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(body -> {
                    assertThat(body).contains("Please sign in");
                    assertNoLeak(body.getBytes(StandardCharsets.UTF_8));
                });

        assertSingleAudit("trace.body.read_denied", null, false);
    }

    private void expectGenericDecryptFailure() {
        rest.get().uri("/api/traces/{id}/body", RUN_ID)
                .header("X-Trace-Access-Reason", "review")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody().consumeWith(response -> assertNoLeak(response.getResponseBody()));
    }

    private void expectForbidden(String token) {
        RestTestClient.RequestHeadersSpec<?> request = rest.get().uri("/api/traces/{id}/body", RUN_ID)
                .header("X-Trace-Access-Reason", "review");
        if (token != null) {
            request = request.headers(headers -> headers.setBearerAuth(token));
        }
        request.exchange()
                .expectStatus().isForbidden()
                .expectBody().consumeWith(response -> assertNoLeak(response.getResponseBody()));
    }

    private void login(String username) {
        rest.post().uri("/api/auth/login?username={username}&password=veridex", username)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo(username);
    }

    private void logout() {
        rest.post().uri("/api/auth/logout").exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();
    }

    private void insertBody(Instant expiresAt, String plaintext) {
        jdbc.update("INSERT INTO query_run (id, user_id, question, status, created_at) "
                + "VALUES (?, ?, '[REDACTED]', 'FAILED', now())", RUN_ID, ADMIN);
        var encrypted = crypto.encrypt(RUN_ID, (short) 1, plaintext.getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO trace_body (query_run_id, capture_policy, encrypted_body, encryption_key_id, "
                        + "nonce, schema_version, created_at, expires_at) VALUES (?, 'ALL', ?, ?, ?, 1, now(), ?)",
                RUN_ID, encrypted.ciphertext(), encrypted.keyId(), encrypted.nonce(),
                java.sql.Timestamp.from(expiresAt));
    }

    private void clearAudits() {
        jdbc.update("DELETE FROM audit_event WHERE resource_type = 'trace_body'");
    }

    private void assertSingleAudit(String action, String forbiddenValue, boolean expectReason) {
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT action, resource_type, resource_id, details::text AS details
                FROM audit_event
                WHERE resource_type = 'trace_body'
                ORDER BY occurred_at
                """);
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("action")).isEqualTo(action);
        assertThat(event.get("resource_type")).isEqualTo("trace_body");
        assertThat(event.get("resource_id")).isEqualTo(RUN_ID);
        String details = String.valueOf(event.get("details"));
        assertThat(details).doesNotContain(PLAINTEXT, "encrypted_body", "nonce", "ciphertext", "test-key");
        if (forbiddenValue != null) {
            assertThat(details).doesNotContain(forbiddenValue);
        }
        if (expectReason) {
            assertThat(details).contains("Incident #42 / review");
        }
    }

    private static void assertNoLeak(byte[] body) {
        String response = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        assertThat(response).doesNotContain(PLAINTEXT, "trace body decryption", "retired-secret-key", "test-key");
    }

    private static String envelope(String material) {
        return "{\"outcome\":\"FAILED\",\"material\":\"" + material + "\"}";
    }
}
