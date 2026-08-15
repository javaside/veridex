package io.veridex.knowledge;

import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import io.veridex.support.RabbitContainerConfiguration;
import java.util.List;
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
class KnowledgeApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired RestTestClient rest;

    private static final JsonMapper JSON = new JsonMapper();

    private void loginAs(String username) {
        rest.post().uri("/api/auth/login?username=" + username + "&password=veridex")
                .exchange()
                .expectStatus().isOk()
                .expectBody().consumeWith(response -> {});
    }

    @Test
    void createKnowledgeBaseThenUploadDocument() throws Exception {
        loginAs("admin");

        var create = rest.post().uri("/api/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"产品手册\",\"description\":\"产品文档\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .returnResult();
        String kbId = extractId(create.getResponseBody());

        rest.post().uri("/api/knowledge-bases/{kbId}/documents", kbId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart("intro.md", "# 产品介绍\n\n内容"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UPLOADED");

        rest.get().uri("/api/knowledge-bases/{kbId}/documents", kbId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].filename").isEqualTo("intro.md");
    }

    @Test
    void employeeWithoutGrantCannotUpload() throws Exception {
        // admin 建库（owner=admin），employee 无 MANAGE grant，上传被拒
        loginAs("admin");
        var create = rest.post().uri("/api/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"受限库\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .returnResult();
        String kbId = extractId(create.getResponseBody());

        // 切换到 employee 会话（新登录覆盖 session）
        loginAs("employee");
        rest.post().uri("/api/knowledge-bases/{kbId}/documents", kbId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart("x.md", "x"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().isEmpty();
    }

    @Test
    void unsupportedDocumentTypeKeepsKnowledgeBadRequestContract() throws Exception {
        loginAs("admin");
        var create = rest.post().uri("/api/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"格式检查库\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .returnResult();
        String kbId = extractId(create.getResponseBody());

        rest.post().uri("/api/knowledge-bases/{kbId}/documents", kbId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipart("unsupported.exe", "x"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().isEmpty();
    }

    // 注：Boot 4 的 RestTestClient 实例跨测试方法共享 session，无法在同一实例上验证
    // "未认证 → 302"（前一测试的登录 session 会残留）。该语义由 AuthFlowIntegrationTest
    // 的 unknownUserLoginIsRejected（401）与 SecurityConfig 保证。

    private static LinkedMultiValueMap<String, Object> multipart(String filename, String content) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.put("file", List.of(new ByteArrayResource(content.getBytes()) {
            @Override
            public String getFilename() { return filename; }
        }));
        return form;
    }

    private static String extractId(byte[] body) throws Exception {
        JsonNode root = JSON.readTree(body);
        return root.path("id").asText();
    }
}
