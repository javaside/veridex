package io.veridex.qa;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.domain.Document;
import io.veridex.knowledge.domain.DocumentRepository;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.domain.DocumentVersionRepository;
import io.veridex.knowledge.domain.KnowledgeBase;
import io.veridex.shared.infrastructure.embedding.DeterministicEmbeddingModel;
import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.OpenSearchContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import io.veridex.support.RabbitContainerConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * QA 端到端测试共享夹具：登录、知识库+READY 文档+发布、SSE 问答与解析。
 */
@Testcontainers
@Import({MinioContainerConfiguration.class, RabbitContainerConfiguration.class,
        OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})
abstract class QaTestFixture extends PostgresIntegrationTest {

    @LocalServerPort int port;

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository versions;
    @Autowired DocumentService documentService;
    @Autowired ObjectStorage storage;

    protected static final JsonMapper JSON = new JsonMapper();
    protected static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    protected static final UUID EMPLOYEE = UUID.fromString("00000000-0000-0000-0000-000000000003");

    protected static final String LEAVE_CHUNK =
            "员工请假需提前两个工作日向直属主管提交书面申请，经审批后生效；连续请假超过五个工作日的，还需报人力资源部备案。";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    protected String base() {
        return "http://localhost:" + port;
    }

    /** 登录并返回 Set-Cookie（会话凭证）。 */
    protected String login(String username) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base() + "/api/auth/login?username=" + username + "&password=veridex"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isLessThan(300);
        return resp.headers().firstValue("Set-Cookie").orElseThrow();
    }

    /** 发起问答，阻塞读取完整 SSE 流（SseEmitter complete 后 EOF）。 */
    protected String ask(String session, String question, List<String> kbIds, String conversationId) throws Exception {
        var body = new StringBuilder("{\"question\":\"" + question + "\",\"knowledgeBaseIds\":[");
        for (int i = 0; i < kbIds.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append('"').append(kbIds.get(i)).append('"');
        }
        body.append(']');
        if (conversationId != null) {
            body.append(",\"conversationId\":\"").append(conversationId).append('"');
        }
        body.append('}');

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/qa/ask"))
                .header("Content-Type", "application/json")
                .header("Cookie", session)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isLessThan(300);
        return resp.body();
    }

    /** 建知识库 + 造 READY 文档版本（写 MinIO chunks/parsed）。返回 kbId。 */
    protected String seedKnowledgeBase(String name, String documentName, String chunkText) {
        KnowledgeBase kb = knowledgeBases.createKnowledgeBase(ADMIN, name, "测试知识库");
        String kbId = kb.getId().toString();

        Document doc = documents.save(new Document(kb.getId(), documentName, "text/markdown",
                chunkText.getBytes(StandardCharsets.UTF_8).length, ADMIN));
        String objectKey = kb.getId() + "/" + doc.getId() + "/v1/" + documentName;
        DocumentVersion version = versions.save(new DocumentVersion(doc.getId(), 1, objectKey, "deadbeef"));
        documentService.markProcessing(version.getId());

        String chunksJson = JSON.writeValueAsString(List.of(
                Map.of("index", 0, "text", chunkText, "title", documentName, "structurePath", "1")));
        storage.put(objectKey + ".chunks.json",
                new java.io.ByteArrayInputStream(chunksJson.getBytes(StandardCharsets.UTF_8)),
                "application/json", chunksJson.getBytes(StandardCharsets.UTF_8).length);
        storage.put(objectKey + ".parsed.json",
                new java.io.ByteArrayInputStream(chunkText.getBytes(StandardCharsets.UTF_8)),
                "text/plain; charset=utf-8", chunkText.getBytes(StandardCharsets.UTF_8).length);

        documentService.markReady(version.getId(), 1);
        return kbId;
    }

    protected void publish(String session, String kbId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base() + "/api/knowledge-bases/" + kbId + "/releases/publish"))
                .header("Cookie", session)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isLessThan(300);
    }

    protected List<String> eventNames(String sseBody) {
        List<String> names = new ArrayList<>();
        for (String block : sseBody.split("\n\n")) {
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    names.add(line.substring("event:".length()).trim());
                }
            }
        }
        return names;
    }

    protected String eventData(String sseBody, String eventName) {
        for (String block : sseBody.split("\n\n")) {
            String event = null;
            String data = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data = line.substring("data:".length()).trim();
                }
            }
            if (eventName.equals(event)) {
                return data;
            }
        }
        return null;
    }

    protected String get(String session, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Cookie", session)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isLessThan(300);
        return resp.body();
    }

    protected static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("no key " + key + " in " + json);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        return json.substring(valueStart, end);
    }

    protected static String extractFirstId(String json) {
        String marker = "\"id\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("no id in " + json);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        return json.substring(valueStart, end);
    }
}
