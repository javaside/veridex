package io.veridex.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.configuration.api.ConfigurationProfileQuery;
import io.veridex.configuration.api.ProfileDefaults;
import io.veridex.conversation.api.ConversationService;
import io.veridex.conversation.api.ConversationView;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationEvent;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.GenerationService;
import io.veridex.knowledge.api.KnowledgeScopeQuery;
import io.veridex.qa.api.AskRequest;
import io.veridex.qa.api.QaEvent;
import io.veridex.qa.application.QuestionAnsweringServiceImpl;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.retrieval.api.HybridSearchResult;
import io.veridex.retrieval.api.HybridSearchService;
import io.veridex.retrieval.api.RankedHitView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.VeridexObservability;
import io.veridex.trace.api.QueryRunRecorder;
import io.veridex.trace.api.TraceBodyCapture;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 问答编排（设计 §4.4/§4.5/§5）：事件顺序、成功落库、失败不落 assistant、取消幂等。
 */
@ExtendWith(MockitoExtension.class)
class QuestionAnsweringServiceTest {

    @Mock KnowledgeScopeQuery knowledgeScope;
    @Mock ConversationService conversations;
    @Mock QueryRunRecorder recorder;
    @Mock TraceBodyCapture traceBodyCapture;
    @Mock HybridSearchService hybridSearch;
    @Mock GenerationService generation;
    @Mock ConfigurationProfileQuery profileQuery;
    @Spy VeridexObservability observability = new VeridexObservability(new SimpleMeterRegistry(), ObservationRegistry.create());

    @InjectMocks QuestionAnsweringServiceImpl service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID KB = UUID.randomUUID();
    private static final UUID CONV = UUID.randomUUID();

    @BeforeEach
    void stubProfile() {
        lenient().when(profileQuery.activeProfileConfig()).thenReturn(ProfileDefaults.defaults());
    }

    private static EvidencePiece evidence() {
        return new EvidencePiece(1, KB, UUID.randomUUID(), 0, "请假制度", "1",
                "员工请假需提前两个工作日提交申请，经审批后生效。");
    }

    @Test
    void emptyScopeRefusesAccessRestricted() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of());
        StepVerifier.create(service.ask(USER, new AskRequest("制度", List.of(KB), null)))
                .expectNextMatches(e -> e instanceof QaEvent.AnswerRefused r
                        && r.reason().equals("ACCESS_RESTRICTED")
                        && r.message().equals("当前可访问知识范围内证据不足"))
                .verifyComplete();
    }

    @Test
    void happyPathEmitsStreamingEventsWithCitations() {
        UUID ver = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, KB, ver, 0, "请假制度", "1",
                "员工请假需提前两个工作日提交申请，经审批后生效。"));
        var searchResult = new HybridSearchResult(evidence, List.of(new RankedHitView(
                KB, ver, 0, "BM25", 2.0, null, 2.0, 1, true, null)), List.of());
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(eq(USER), eq(List.of(KB)), eq(List.of(KB)), eq("请假"), any())).thenReturn(searchResult);
        when(generation.stream(eq("请假"), eq(evidence), any(), any())).thenReturn(Flux.just(
                new GenerationEvent.Delta("根据《请假制度》[1]"),
                new GenerationEvent.Completed(new GenerationResult("根据《请假制度》[1]",
                        List.of(new CitationView(1, UUID.randomUUID(), ver, 0, "请假制度", "[1]", "VALID")),
                        null, "deterministic", "deterministic", 10, 20, 5, 3, false, "abc", List.of()))));

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .expectNextMatches(e -> e instanceof QaEvent.AnswerDelta)
                .expectNextMatches(e -> e instanceof QaEvent.CitationAvailable)
                .expectNextMatches(e -> e instanceof QaEvent.AnswerCompleted)
                .verifyComplete();
        verify(recorder).complete(any());
        verify(recorder).markRetrieving(any(), argThat(hits -> hits.size() == 1));
        verify(recorder).recordGeneration(any(), argThat(gen -> gen.provider().equals("deterministic")));
        verify(conversations).addMessage(eq(CONV), eq("ASSISTANT"), eq("根据《请假制度》[1]"), any());
    }

    @Test
    void refusalEmitsAnswerRefusedWithoutModelCall() {
        UUID ver = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, KB, ver, 0, "请假制度", "1",
                "员工请假需提前两个工作日提交申请。"));
        var searchResult = new HybridSearchResult(evidence, List.of(), List.of());
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(eq(USER), eq(List.of(KB)), eq(List.of(KB)), eq("请假"), any())).thenReturn(searchResult);
        when(generation.stream(eq("请假"), eq(evidence), any(), any())).thenReturn(Flux.just(
                new GenerationEvent.Refused(new GenerationResult(null, List.of(),
                        RefusalReason.INSUFFICIENT_EVIDENCE, "deterministic", "deterministic", 0, 0, 0, 0, true, null, List.of()))));

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .expectNextMatches(e -> e instanceof QaEvent.AnswerRefused)
                .verifyComplete();
        verify(recorder).refuse(any(), eq(RefusalReason.INSUFFICIENT_EVIDENCE));
        verify(conversations, never()).addMessage(eq(CONV), eq("ASSISTANT"), any(), any());
    }

    @Test
    void invalidCitationFailsRunWithoutPersistingAssistant() {
        UUID ver = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, KB, ver, 0, "请假制度", "1",
                "员工请假需提前两个工作日提交申请。"));
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(eq(USER), eq(List.of(KB)), eq(List.of(KB)), eq("请假"), any()))
                .thenReturn(new HybridSearchResult(evidence, List.of(), List.of()));
        // 引用终检在 GenerationServiceImpl 内完成；编排层收到的是终检异常
        when(generation.stream(eq("请假"), eq(evidence), any(), any())).thenReturn(Flux.error(
                new io.veridex.generation.api.InvalidCitationException("invalid")));

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .expectNextMatches(e -> e instanceof QaEvent.RunFailed)
                .verifyComplete();
        verify(recorder).fail(any(), eq("INVALID_CITATION"));
        verify(conversations, never()).addMessage(eq(CONV), eq("ASSISTANT"), any(), any());
        verify(recorder, never()).complete(any());
    }

    @Test
    void modelErrorFailsRunWithoutPersistingAssistant() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(eq(USER), eq(List.of(KB)), eq(List.of(KB)), eq("请假"), any()))
                .thenReturn(new HybridSearchResult(List.of(evidence()), List.of(), List.of()));
        when(generation.stream(any(), any(), any(), any()))
                .thenReturn(Flux.error(new io.veridex.generation.api.ModelTimeoutException("t")));

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .expectNextMatches(e -> e instanceof QaEvent.RunFailed)
                .verifyComplete();
        verify(recorder).fail(any(), eq("MODEL_TIMEOUT"));
        verify(conversations, never()).addMessage(eq(CONV), eq("ASSISTANT"), any(), any());
    }

    @Test
    void cancellationCancelsRunAndPropagates() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(eq(USER), eq(List.of(KB)), eq(List.of(KB)), eq("请假"), any()))
                .thenReturn(new HybridSearchResult(List.of(evidence()), List.of(), List.of()));
        when(generation.stream(any(), any(), any(), any())).thenReturn(Flux.never());

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .thenCancel()
                .verify();
        verify(recorder).cancel(any());
        verify(conversations, never()).addMessage(eq(CONV), eq("ASSISTANT"), any(), any());
    }

    @Test
    void retrievalFailureEmitsRunFailed() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.create(any(), any())).thenReturn(new ConversationView(UUID.randomUUID(), "t", java.time.Instant.now()));
        when(recorder.start(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(eq(USER), eq(List.of(KB)), eq(List.of(KB)), eq("请假"), any())).thenThrow(new RuntimeException("opensearch down"));
        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), null)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RunFailed)
                .verifyComplete();
        verify(recorder).fail(any(), anyString());
    }

    @Test
    void createsConversationWhenNoneProvided() {
        var conversation = new ConversationView(UUID.randomUUID(), "请假", java.time.Instant.now());
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.create(USER, "请假")).thenReturn(conversation);
        when(recorder.start(any(), eq(conversation.id()), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(eq(USER), eq(List.of(KB)), eq(List.of(KB)), eq("请假"), any())).thenReturn(
                new HybridSearchResult(List.of(), List.of(), List.of()));
        when(generation.stream(eq("请假"), eq(List.of()), any(), any())).thenReturn(Flux.just(
                new GenerationEvent.Refused(new GenerationResult(null, List.of(),
                        RefusalReason.NO_RELEVANT_EVIDENCE, "deterministic", "deterministic", 0, 0, 0, 0, true, null, List.of()))));

        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), null)))
                .expectNextMatches(e -> e instanceof QaEvent.RunStarted)
                .expectNextMatches(e -> e instanceof QaEvent.RetrievalCompleted)
                .expectNextMatches(e -> e instanceof QaEvent.AnswerRefused)
                .verifyComplete();
        verify(conversations).create(USER, "请假");
    }

    @Test
    void recordsCompletedQaRunWithBoundedTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        QuestionAnsweringServiceImpl instrumented = new QuestionAnsweringServiceImpl(knowledgeScope, conversations,
                recorder, hybridSearch, generation, profileQuery,
                new VeridexObservability(meters, ObservationRegistry.create()), traceBodyCapture);
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(eq(USER), eq(List.of(KB)), eq(List.of(KB)), eq("请假"), any()))
                .thenReturn(new HybridSearchResult(List.of(), List.of(), List.of()));
        when(generation.stream(eq("请假"), eq(List.of()), any(), any())).thenReturn(Flux.just(
                new GenerationEvent.Refused(new GenerationResult(null, List.of(),
                        RefusalReason.NO_RELEVANT_EVIDENCE, "deterministic", "deterministic", 0, 0, 0, 0, true, null, List.of()))));

        StepVerifier.create(instrumented.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextCount(3)
                .verifyComplete();

        assertThat(meters.find("veridex.qa.run").tag("outcome", "refused").timer().count()).isEqualTo(1);
        assertThat(meters.find("veridex.qa.run").timer().getId().getTags())
                .allMatch(tag -> !tag.getValue().contains("请假") && !tag.getValue().contains(USER.toString()));
    }

    @Test
    void rejectsConversationOwnedByAnotherUser() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.empty());
        StepVerifier.create(service.ask(USER, new AskRequest("请假", List.of(KB), CONV)))
                .expectNextMatches(e -> e instanceof QaEvent.RunFailed)
                .verifyComplete();
    }
}
