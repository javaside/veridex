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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@Import({MinioContainerConfiguration.class, RabbitContainerConfiguration.class,
        OpenSearchContainerConfiguration.class, DeterministicEmbeddingModel.class})
class QaApiIntegrationTest extends PostgresIntegrationTest {

    @LocalServerPort int port;

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository versions;
    @Autowired DocumentService documentService;
    @Autowired ObjectStorage storage;

    private static final JsonMapper JSON = new JsonMapper();
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final String LEAVE_CHUNK =
            "员工请假需提前两个工作日向直属主管提交书面申请，经审批后生效；连续请假超过五个工作日的，还需报人力资源部备案。";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30)).build();

    private String base() {
        return "http://localhost:" + port;
    }

    /** 登录并返回 Set-Cookie（会话凭证）。 */
    private String login(String username) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base() + "/api/auth/login?username=" + username + "&password=veridex"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isLessThan(300);
        return resp.headers().firstValue("Set-Cookie").orElseThrow();
    }

    /** 发起问答，阻塞读取完整 SSE 流（SseEmitter complete 后 EOF）。 */
    private String ask(String session, String question, List<String> kbIds, String conversationId) throws Exception {
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

    private String seedKnowledgeBaseWithPublishedDocument(String name, String documentName, String chunkText) {
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

    private void publish(String session, String kbId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(base() + "/api/knowledge-bases/" + kbId + "/releases/publish"))
                .header("Cookie", session)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isLessThan(300);
    }

    private List<String> eventNames(String sseBody) {
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

    private String eventData(String sseBody, String eventName) {
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

    private String get(String session, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Cookie", session)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isLessThan(300);
        return resp.body();
    }

    @Test
    void askEmitsStreamingEventSequenceWithCitations() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBaseWithPublishedDocument("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(session, kbId);

        String sse = ask(session, "请假几天", List.of(kbId), null);

        var events = eventNames(sse);
        assertThat(events).containsSubsequence(
                "run.started", "retrieval.completed", "answer.delta", "citation.available", "answer.completed");
        assertThat(events).doesNotContain("answer.refused", "run.failed");
        assertThat(eventData(sse, "citation.available")).contains("VALID");
    }

    @Test
    void unauthorizedEmployeeGetsAccessRestrictedRefusal() throws Exception {
        String adminSession = login("admin");
        String kbId = seedKnowledgeBaseWithPublishedDocument("保密库", "机密.md", LEAVE_CHUNK);
        publish(adminSession, kbId);

        String employeeSession = login("employee");
        String sse = ask(employeeSession, "请假", List.of(kbId), null);

        var events = eventNames(sse);
        assertThat(events).contains("answer.refused");
        assertThat(events).doesNotContain("answer.delta");
        assertThat(eventData(sse, "answer.refused")).contains("ACCESS_RESTRICTED");
    }

    @Test
    void conversationOwnershipIsEnforced() throws Exception {
        String adminSession = login("admin");
        String kbId = seedKnowledgeBaseWithPublishedDocument("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(adminSession, kbId);

        // admin 提问创建会话
        String adminSse = ask(adminSession, "请假几天", List.of(kbId), null);
        String startedData = eventData(adminSse, "run.started");
        assertThat(startedData).isNotNull();
        String conversationId = extractJsonString(startedData, "conversationId");

        // admin 授权 employee 对该库 VIEW，但会话仍属于 admin
        knowledgeBases.grantAccess(UUID.fromString(kbId),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                io.veridex.knowledge.domain.GrantLevel.VIEW);

        String employeeSession = login("employee");
        String sse = ask(employeeSession, "请假几天", List.of(kbId), conversationId);
        assertThat(eventNames(sse)).contains("run.failed");
    }

    @Test
    void conversationsAndMessagesAreListed() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBaseWithPublishedDocument("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(session, kbId);
        ask(session, "请假几天", List.of(kbId), null);

        String conversations = get(session, "/api/qa/conversations");
        assertThat(conversations).contains("请假");
        String convId = extractFirstId(conversations);

        String messages = get(session, "/api/qa/conversations/" + convId + "/messages");
        assertThat(messages).contains("请假几天").contains("根据《");
    }

    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("no key " + key + " in " + json);
        }
        int valueStart = start + marker.length();
        int end = json.indexOf('"', valueStart);
        return json.substring(valueStart, end);
    }

    private static String extractFirstId(String json) {
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
