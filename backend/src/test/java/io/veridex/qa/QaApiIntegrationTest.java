package io.veridex.qa;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.knowledge.domain.GrantLevel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QaApiIntegrationTest extends QaTestFixture {

    @Test
    void askEmitsStreamingEventSequenceWithCitations() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBase("制度库", "请假制度.md", LEAVE_CHUNK);
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
        String kbId = seedKnowledgeBase("保密库", "机密.md", LEAVE_CHUNK);
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
        String kbId = seedKnowledgeBase("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(adminSession, kbId);

        String adminSse = ask(adminSession, "请假几天", List.of(kbId), null);
        String conversationId = extractJsonString(eventData(adminSse, "run.started"), "conversationId");

        // admin 授权 employee 对该库 VIEW，但会话仍属于 admin
        knowledgeBases.grantAccess(UUID.fromString(kbId), EMPLOYEE, GrantLevel.VIEW);

        String employeeSession = login("employee");
        String sse = ask(employeeSession, "请假几天", List.of(kbId), conversationId);
        assertThat(eventNames(sse)).contains("run.failed");
    }

    @Test
    void firstAnswerDeltaArrivesBeforeStreamCompletes() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBase("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(session, kbId);

        String head = askUntilEvent(session, "请假几天", List.of(kbId), "answer.delta");

        // 读到首个 answer.delta 时流尚未结束：必须还没有 answer.completed
        assertThat(head).contains("event:answer.delta");
        assertThat(head).doesNotContain("event:answer.completed");
    }

    @Test
    void conversationsAndMessagesAreListed() throws Exception {
        String session = login("admin");
        String kbId = seedKnowledgeBase("制度库", "请假制度.md", LEAVE_CHUNK);
        publish(session, kbId);
        ask(session, "请假几天", List.of(kbId), null);

        String conversations = get(session, "/api/qa/conversations");
        assertThat(conversations).contains("请假");
        String convId = extractFirstId(conversations);

        String messages = get(session, "/api/qa/conversations/" + convId + "/messages");
        assertThat(messages).contains("请假几天").contains("根据《");
    }
}
