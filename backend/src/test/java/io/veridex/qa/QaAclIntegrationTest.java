package io.veridex.qa;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.knowledge.domain.Document;
import io.veridex.knowledge.domain.DocumentRepository;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.domain.DocumentVersionRepository;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 权限与降级门禁（Roadmap Phase 3 出口）：
 * 1. 越权库召回为 0（ACL 门禁）；
 * 2. 紧急下线文档立即从检索排除；
 * 3. 非拒答答案必须携带合法引用。
 */
class QaAclIntegrationTest extends QaTestFixture {

    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository versions;

    /** 把知识库内所有文档版本置为 OFFLINE（紧急下线语义）。 */
    private void setAllVersionsOffline(String kbId) {
        for (Document doc : documents.findByKnowledgeBaseIdOrderByCreatedAtDesc(UUID.fromString(kbId))) {
            versions.findFirstByDocumentIdOrderByVersionNoDesc(doc.getId()).ifPresent(v -> {
                try {
                    java.lang.reflect.Field field = DocumentVersion.class.getDeclaredField("status");
                    field.setAccessible(true);
                    field.set(v, DocumentVersionStatus.OFFLINE);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("cannot set OFFLINE on version " + v.getId(), e);
                }
                versions.save(v);
            });
        }
    }

    @Test
    void unauthorizedKnowledgeBaseIsNeverRetrieved() throws Exception {
        String adminSession = login("admin");
        String kbId = seedKnowledgeBase("保密库", "机密制度.md", LEAVE_CHUNK);
        publish(adminSession, kbId);

        String employeeSession = login("employee");
        String sse = ask(employeeSession, "请假", List.of(kbId), null);

        // employee 对库无 VIEW → 交集为空 → 拒答，零检索、零回答
        var events = eventNames(sse);
        assertThat(events).contains("answer.refused");
        assertThat(events).doesNotContain("answer.delta", "citation.available", "answer.completed");
    }

    @Test
    void offlineDocumentIsExcludedFromRetrieval() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBase("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(session, kbId);

        // 发布后紧急下线该文档版本 → 在线过滤立即排除 → 检索为空 → 拒答
        setAllVersionsOffline(kbId);

        String sse = ask(session, "请假几天", List.of(kbId), null);
        var events = eventNames(sse);
        assertThat(events).contains("answer.refused");
        assertThat(events).doesNotContain("answer.delta", "citation.available", "answer.completed");
    }

    @Test
    void nonRefusalAnswerAlwaysCarriesValidCitations() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBase("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(session, kbId);

        String sse = ask(session, "请假几天", List.of(kbId), null);

        var events = eventNames(sse);
        assertThat(events).contains("answer.completed");
        String citations = eventData(sse, "citation.available");
        assertThat(citations).isNotNull();
        assertThat(citations).contains("VALID").doesNotContain("INVALID");
    }
}
