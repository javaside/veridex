package io.veridex.security;

import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import io.veridex.support.RabbitContainerConfiguration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureRestTestClient
@Testcontainers
@Import({MinioContainerConfiguration.class, RabbitContainerConfiguration.class})
class AuthorizationCacheIsolationIntegrationTest extends PostgresIntegrationTest {

    private static final JsonMapper JSON = new JsonMapper();

    @Autowired RestTestClient rest;

    private String csrfToken;

    @BeforeEach
    void clearSession() {
        rest.post().uri("/api/auth/logout").exchange().expectStatus().isNoContent().expectBody().isEmpty();
        csrfToken = null;
    }

    @Test
    void employeeCannotEnumerateProtectedDocumentPreview() throws Exception {
        String versionId = createDocumentAsAdmin();

        loginAs("employee");

        rest.get().uri("/api/documents/{documentId}/versions/{versionId}/parsed", UUID.randomUUID(), versionId)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().consumeWith(response -> {});
    }

    @Test
    void missingVersionReturnsNotFound() throws Exception {
        loginAs("admin");

        rest.get().uri("/api/documents/{documentId}/versions/{versionId}/parsed",
                        UUID.randomUUID(), UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().consumeWith(response -> {});
    }

    private String createDocumentAsAdmin() throws Exception {
        loginAs("admin");
        String kbId = createKnowledgeBase("授权库");
        return uploadDocument(kbId, "guide.md", "# 指南\n\n内容");
    }

    private String createKnowledgeBase(String name) throws Exception {
        byte[] body = rest.post().uri("/api/knowledge-bases")
                .header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"" + name + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return JSON.readTree(body).path("id").asText();
    }

    private String uploadDocument(String kbId, String filename, String content) throws Exception {
        byte[] body = rest.post().uri("/api/knowledge-bases/{kbId}/documents", kbId)
                .header("X-XSRF-TOKEN", csrfToken)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart(filename, content))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return JSON.readTree(body).path("id").asText();
    }

    private void loginAs(String username) throws Exception {
        rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .expectBody().consumeWith(response -> {});
        byte[] body = rest.get().uri("/api/auth/csrf")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        csrfToken = JSON.readTree(body).path("token").asText();
    }

    private static LinkedMultiValueMap<String, Object> multipart(String filename, String content) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.put("file", List.of(new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        }));
        return form;
    }
}
