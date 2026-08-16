package io.veridex.qa.application;

import io.veridex.conversation.api.ConversationService;
import io.veridex.conversation.api.ConversationView;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.PromptMessageView;
import io.veridex.generation.api.GenerationService;
import io.veridex.knowledge.api.KnowledgeScopeQuery;
import io.veridex.qa.api.AskRequest;
import io.veridex.qa.api.QaEvent;
import io.veridex.retrieval.api.HybridSearchResult;
import io.veridex.retrieval.api.HybridSearchService;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.ObservationName;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import io.veridex.trace.api.QueryRunRecorder;
import io.veridex.trace.api.QueryRunRecorder.CitationRecord;
import io.veridex.trace.api.QueryRunRecorder.GenerationRecord;
import io.veridex.trace.api.QueryRunRecorder.RetrievalHitRecord;
import io.veridex.trace.api.TraceBodyCapture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 问答编排：知识范围交集 → 会话归属校验 → QueryRun 创建 → 混合检索 → 生成/拒答 →
 * 引用校验 → 事件序列。任何未捕获异常兜底为 run.failed（双路检索失败报系统错误，不伪装无答案）。
 */
@Service
public class QuestionAnsweringServiceImpl implements QuestionAnsweringService {

    private static final int HISTORY_TURNS = 6;
    private static final int STREAM_CHUNK = 8;

    private final KnowledgeScopeQuery knowledgeScope;
    private final ConversationService conversations;
    private final QueryRunRecorder recorder;
    private final HybridSearchService hybridSearch;
    private final GenerationService generation;
    private final VeridexObservability observability;
    private final TraceBodyCapture traceBodyCapture;

    public QuestionAnsweringServiceImpl(KnowledgeScopeQuery knowledgeScope, ConversationService conversations,
                                        QueryRunRecorder recorder, HybridSearchService hybridSearch,
                                        GenerationService generation, VeridexObservability observability,
                                        TraceBodyCapture traceBodyCapture) {
        this.knowledgeScope = knowledgeScope;
        this.conversations = conversations;
        this.recorder = recorder;
        this.hybridSearch = hybridSearch;
        this.generation = generation;
        this.observability = observability;
        this.traceBodyCapture = traceBodyCapture;
    }

    @Override
    public List<QaEvent> ask(UUID userId, AskRequest request) {
        UUID[] runRef = new UUID[1];
        boolean existingConversation = request.conversationId() != null;
        var observation = observability.start(ObservationName.QA_RUN,
                TelemetryTag.conversation(existingConversation ? TelemetryOutcome.Conversation.EXISTING
                        : TelemetryOutcome.Conversation.NEW));
        try {
            List<QaEvent> result = execute(userId, request, runRef);
            observation.success(TelemetryTag.qaOutcome(classify(result)));
            return result;
        } catch (Exception e) {
            observation.failure(TelemetryErrorCode.classify(e));
            if (runRef[0] != null) {
                String errorCode = TelemetryErrorCode.classify(e).name();
                recorder.fail(runRef[0], errorCode);
                traceBodyCapture.capture(runRef[0], TraceBodyCapture.TerminalOutcome.FAILED, errorCode,
                        new TraceBodyCapture.TraceBodyMaterial(request.question(), List.of(), null, List.of(), List.of()));
            }
            String message = e.getMessage() != null ? e.getMessage() : "系统错误";
            return List.of(new QaEvent.RunFailed(message));
        } finally {
            observation.close();
        }
    }

    private static TelemetryOutcome.Qa classify(List<QaEvent> events) {
        return events.stream().anyMatch(QaEvent.AnswerRefused.class::isInstance)
                ? TelemetryOutcome.Qa.REFUSED
                : TelemetryOutcome.Qa.COMPLETED;
    }

    private List<QaEvent> execute(UUID userId, AskRequest request, UUID[] runRef) {
        List<UUID> scope = knowledgeScope.resolve(userId, request.knowledgeBaseIds());
        if (scope.isEmpty()) {
            return List.of(new QaEvent.AnswerRefused("ACCESS_RESTRICTED", "当前可访问知识范围内证据不足"));
        }

        UUID conversationId = request.conversationId();
        if (conversationId == null) {
            ConversationView created = conversations.create(userId, truncate(request.question(), 80));
            conversationId = created.id();
        } else if (conversations.findOwned(userId, conversationId).isEmpty()) {
            throw new IllegalStateException("会话不存在或无权访问");
        }

        String normalized = request.question().trim();
        UUID runId = recorder.start(userId, conversationId, scope, normalized);
        runRef[0] = runId;
        List<QaEvent> events = new ArrayList<>();
        events.add(new QaEvent.RunStarted(runId, conversationId));

        var history = conversations.recentMessages(conversationId, HISTORY_TURNS);
        conversations.addMessage(conversationId, "USER", request.question(), runId);

        HybridSearchResult searchResult = hybridSearch.search(userId, scope, request.knowledgeBaseIds(), normalized);
        events.add(new QaEvent.RetrievalCompleted(searchResult.evidence().size()));
        recorder.markRetrieving(runId, searchResult.hits().stream()
                .map(h -> new RetrievalHitRecord(h.knowledgeBaseId(), h.documentVersionId(), h.chunkIndex(),
                        h.channel(), h.bm25Score(), h.vectorScore(), h.fusionScore(), h.rank(),
                        h.enteredContext(), h.filterReason()))
                .toList());

        GenerationResult result = generation.generate(normalized, searchResult.evidence(), history);
        recorder.markGenerating(runId, new GenerationRecord(result.model(), result.inputTokens(),
                result.outputTokens(), result.durationMs(),
                searchResult.degradations().isEmpty() ? null : String.join("; ", searchResult.degradations()),
                result.contextHash()));

        if (result.refusalReason() != null) {
            events.add(new QaEvent.AnswerRefused(result.refusalReason().name(),
                    refusalMessage(result.refusalReason())));
            recorder.refuse(runId, result.refusalReason());
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.REFUSED, result.refusalReason().name(),
                    material(request.question(), result, searchResult.evidence()));
            return events;
        }

        recorder.addCitations(runId, result.citations().stream()
                .map(c -> new CitationRecord(c.citationIndex(), c.documentVersionId(), c.chunkIndex(),
                        c.sourceLocation(), c.citationText(), c.validationStatus()))
                .toList());
        for (int i = 0; i < result.answer().length(); i += STREAM_CHUNK) {
            events.add(new QaEvent.AnswerDelta(
                    result.answer().substring(i, Math.min(result.answer().length(), i + STREAM_CHUNK))));
        }
        events.add(new QaEvent.CitationAvailable(result.citations()));
        conversations.addMessage(conversationId, "ASSISTANT", result.answer(), runId);
        events.add(new QaEvent.AnswerCompleted(runId));
        recorder.complete(runId);
        traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.COMPLETED, null,
                material(request.question(), result, searchResult.evidence()));
        return events;
    }

    private static TraceBodyCapture.TraceBodyMaterial material(String question, GenerationResult result,
                                                                List<io.veridex.retrieval.api.EvidencePiece> evidence) {
        var prompts = result.promptMessages().stream()
                .map(p -> new TraceBodyCapture.PromptMessage(p.role(), p.content())).toList();
        var evidenceSnapshots = evidence.stream()
                .map(e -> new TraceBodyCapture.EvidenceSnapshot(e.citationIndex(), e.documentVersionId(),
                        e.chunkIndex(), e.title(), e.structurePath(), e.text())).toList();
        var citations = result.citations().stream()
                .map(c -> new TraceBodyCapture.CitationSnapshot(c.citationIndex(), c.documentVersionId(),
                        c.chunkIndex(), c.sourceLocation(), c.citationText(), c.validationStatus())).toList();
        return new TraceBodyCapture.TraceBodyMaterial(question, prompts, result.answer(), evidenceSnapshots, citations);
    }

    private static String refusalMessage(RefusalReason reason) {
        return switch (reason) {
            case ACCESS_RESTRICTED -> "当前可访问知识范围内证据不足";
            default -> "未能在授权资料中找到足够依据回答该问题";
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
