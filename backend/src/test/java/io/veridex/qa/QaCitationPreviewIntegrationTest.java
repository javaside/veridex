package io.veridex.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 引用预览闭环：citation.available 事件必须携带 documentId，前端才能调用 chunks API 取原文。
 */
class QaCitationPreviewIntegrationTest extends QaTestFixture {

    @Test
    void citationAvailableCarriesDocumentIdForPreview() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBase("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(session, kbId);

        String sse = ask(session, "请假几天", List.of(kbId), null);

        var events = eventNames(sse);
        assertThat(events).contains("answer.completed");

        String citations = eventData(sse, "citation.available");
        assertThat(citations).isNotNull();
        assertThat(citations).contains("documentId").contains("VALID");
        assertThat(citations).doesNotContain("\"documentId\":null");
    }
}
