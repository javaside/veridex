package io.veridex.qa.api;

import io.veridex.generation.api.CitationView;
import java.util.List;
import java.util.UUID;

/**
 * SSE 事件（设计文档 §11.7）：run.started → retrieval.completed → answer.delta*
 * → citation.available → answer.completed / answer.refused / run.failed。
 */
public sealed interface QaEvent {

    record RunStarted(UUID runId, UUID conversationId) implements QaEvent {
    }

    record RetrievalCompleted(int hitCount) implements QaEvent {
    }

    record AnswerDelta(String text) implements QaEvent {
    }

    record CitationAvailable(List<CitationView> citations) implements QaEvent {
    }

    record AnswerCompleted(UUID runId) implements QaEvent {
    }

    record AnswerRefused(String reason, String message) implements QaEvent {
    }

    record RunFailed(String message) implements QaEvent {
    }
}
