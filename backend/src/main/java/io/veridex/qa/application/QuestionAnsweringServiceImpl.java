package io.veridex.qa.application;

import io.veridex.conversation.api.ConversationService;
import io.veridex.conversation.api.ConversationView;
import io.veridex.generation.api.GenerationEvent;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.GenerationService;
import io.veridex.generation.application.GenerationModelException;
import io.veridex.generation.application.InvalidCitationException;
import io.veridex.knowledge.api.KnowledgeScopeQuery;
import io.veridex.qa.api.AskRequest;
import io.veridex.qa.api.QaEvent;
import io.veridex.retrieval.api.EvidencePiece;
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
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * 问答编排（设计 §4.4/§4.5/§5）：cold Flux，订阅后才执行；模型流完成后再引用终检；
 * 客户端断开经 sink.onCancel 传播为上游取消并落 CANCELLED；取消/失败收尾幂等。
 */
@Service
public class QuestionAnsweringServiceImpl implements QuestionAnsweringService {

    private static final int HISTORY_TURNS = 6;

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
    public Flux<QaEvent> ask(UUID userId, AskRequest request) {
        boolean existingConversation = request.conversationId() != null;
        return Flux.<QaEvent>create(sink -> execute(userId, request, sink,
                        observability.start(ObservationName.QA_RUN,
                                TelemetryTag.conversation(existingConversation
                                        ? TelemetryOutcome.Conversation.EXISTING
                                        : TelemetryOutcome.Conversation.NEW))),
                FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void execute(UUID userId, AskRequest request, FluxSink<QaEvent> sink,
                         VeridexObservability.ObservationScope observation) {
        UUID[] runRef = new UUID[1];
        Disposable[] modelSubscription = new Disposable[1];
        sink.onCancel(() -> cancelRun(runRef[0], modelSubscription[0], observation));

        try {
            List<UUID> scope = knowledgeScope.resolve(userId, request.knowledgeBaseIds());
            if (scope.isEmpty()) {
                sink.next(new QaEvent.AnswerRefused("ACCESS_RESTRICTED", "当前可访问知识范围内证据不足"));
                observation.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.REFUSED));
                sink.complete();
                return;
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
            sink.next(new QaEvent.RunStarted(runId, conversationId));

            var history = conversations.recentMessages(conversationId, HISTORY_TURNS);
            conversations.addMessage(conversationId, "USER", request.question(), runId);

            HybridSearchResult searchResult = hybridSearch.search(userId, scope, request.knowledgeBaseIds(), normalized);
            sink.next(new QaEvent.RetrievalCompleted(searchResult.evidence().size()));
            recorder.markRetrieving(runId, searchResult.hits().stream()
                    .map(h -> new RetrievalHitRecord(h.knowledgeBaseId(), h.documentVersionId(), h.chunkIndex(),
                            h.channel(), h.bm25Score(), h.vectorScore(), h.fusionScore(), h.rank(),
                            h.enteredContext(), h.filterReason()))
                    .toList());

            recorder.markGenerating(runId);
            final UUID convId = conversationId;
            modelSubscription[0] = generation.stream(normalized, searchResult.evidence(), history)
                    .subscribe(
                            event -> handleGenerationEvent(event, sink, observation, runId, convId,
                                    request, searchResult),
                            error -> handleGenerationError(error, sink, observation, runId, request,
                                    searchResult),
                            () -> { /* 终端事件由 handleGenerationEvent 的 Completed/Refused 分支负责 */ });
        } catch (RuntimeException e) {
            handleError(e, sink, observation, runRef[0], request);
        }
    }

    private void handleGenerationEvent(GenerationEvent event, FluxSink<QaEvent> sink,
                                       VeridexObservability.ObservationScope observation, UUID runId,
                                       UUID conversationId, AskRequest request,
                                       HybridSearchResult searchResult) {
        if (event instanceof GenerationEvent.Delta delta) {
            sink.next(new QaEvent.AnswerDelta(delta.text()));
            return;
        }
        if (event instanceof GenerationEvent.Refused refused) {
            GenerationResult result = refused.result();
            sink.next(new QaEvent.AnswerRefused(result.refusalReason().name(),
                    refusalMessage(result.refusalReason())));
            recorder.refuse(runId, result.refusalReason());
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.REFUSED,
                    result.refusalReason().name(), material(request.question(), result, searchResult.evidence()));
            observation.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.REFUSED));
            sink.complete();
            return;
        }
        if (event instanceof GenerationEvent.Completed completed) {
            GenerationResult result = completed.result();
            recorder.recordGeneration(runId, new GenerationRecord(result.provider(), result.model(),
                    result.inputTokens(), result.outputTokens(), result.durationMs(),
                    result.firstTokenLatencyMs(),
                    searchResult.degradations().isEmpty() ? null : String.join("; ", searchResult.degradations()),
                    result.contextHash()));
            recorder.addCitations(runId, result.citations().stream()
                    .map(c -> new CitationRecord(c.citationIndex(), c.documentVersionId(), c.chunkIndex(),
                            c.sourceLocation(), c.citationText(), c.validationStatus()))
                    .toList());
            conversations.addMessage(conversationId, "ASSISTANT", result.answer(), runId);
            sink.next(new QaEvent.CitationAvailable(result.citations()));
            sink.next(new QaEvent.AnswerCompleted(runId));
            recorder.complete(runId);
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.COMPLETED, null,
                    material(request.question(), result, searchResult.evidence()));
            observation.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.COMPLETED));
            sink.complete();
        }
    }

    private void handleGenerationError(Throwable error, FluxSink<QaEvent> sink,
                                       VeridexObservability.ObservationScope observation, UUID runId,
                                       AskRequest request, HybridSearchResult searchResult) {
        TelemetryErrorCode code = TelemetryErrorCode.classify(toException(error));
        recorder.fail(runId, code.name());
        traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.FAILED, code.name(),
                new TraceBodyCapture.TraceBodyMaterial(request.question(), List.of(), null,
                        searchResult.evidence().stream()
                                .map(e -> new TraceBodyCapture.EvidenceSnapshot(
                                        e.citationIndex(), e.documentVersionId(), e.chunkIndex(), e.title(),
                                        e.structurePath(), e.text()))
                                .toList(),
                        List.of()));
        observation.failure(code);
        sink.next(new QaEvent.RunFailed(userSafeMessage(error)));
        sink.complete();
    }

    private void cancelRun(UUID runId, Disposable modelSubscription,
                           VeridexObservability.ObservationScope observation) {
        if (modelSubscription != null && !modelSubscription.isDisposed()) {
            modelSubscription.dispose();
        }
        if (runId != null) {
            recorder.cancel(runId);
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.CANCELLED, null,
                    new TraceBodyCapture.TraceBodyMaterial("", List.of(), null, List.of(), List.of()));
        }
        observation.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.CANCELLED));
    }

    private void handleError(RuntimeException e, FluxSink<QaEvent> sink,
                             VeridexObservability.ObservationScope observation, UUID runId,
                             AskRequest request) {
        TelemetryErrorCode code = TelemetryErrorCode.classify(e);
        if (runId != null) {
            recorder.fail(runId, code.name());
            traceBodyCapture.capture(runId, TraceBodyCapture.TerminalOutcome.FAILED, code.name(),
                    new TraceBodyCapture.TraceBodyMaterial(request.question(), List.of(), null, List.of(), List.of()));
        }
        observation.failure(code);
        sink.next(new QaEvent.RunFailed(userSafeMessage(e)));
        sink.complete();
    }

    private static RuntimeException toException(Throwable error) {
        return error instanceof RuntimeException re ? re : new RuntimeException(error);
    }

    private static String userSafeMessage(Throwable error) {
        if (error instanceof InvalidCitationException) {
            return "回答未通过引用校验，未保存本次结果";
        }
        if (error instanceof GenerationModelException) {
            return "模型服务暂时不可用，请稍后重试";
        }
        return "系统错误，请稍后重试";
    }

    private static TraceBodyCapture.TraceBodyMaterial material(String question, GenerationResult result,
                                                                List<EvidencePiece> evidence) {
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
