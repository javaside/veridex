package io.veridex.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.conversation.api.ConversationService;
import io.veridex.conversation.api.ConversationView;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.GenerationService;
import io.veridex.knowledge.api.KnowledgeScopeQuery;
import io.veridex.qa.api.AskRequest;
import io.veridex.qa.api.QaEvent;
import io.veridex.qa.application.QuestionAnsweringService;
import io.veridex.qa.application.QuestionAnsweringServiceImpl;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.retrieval.api.HybridSearchResult;
import io.veridex.retrieval.api.HybridSearchService;
import io.veridex.retrieval.api.RankedHitView;
import io.veridex.shared.RefusalReason;
import io.veridex.trace.api.QueryRunRecorder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuestionAnsweringServiceTest {

    @Mock KnowledgeScopeQuery knowledgeScope;
    @Mock ConversationService conversations;
    @Mock QueryRunRecorder recorder;
    @Mock HybridSearchService hybridSearch;
    @Mock GenerationService generation;

    @InjectMocks QuestionAnsweringServiceImpl service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID KB = UUID.randomUUID();
    private static final UUID CONV = UUID.randomUUID();

    @Test
    void emptyScopeRefusesAccessRestricted() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of());
        var events = service.ask(USER, new AskRequest("制度", List.of(KB), null));
        assertThat(events.get(events.size() - 1)).isInstanceOf(QaEvent.AnswerRefused.class);
        var refused = (QaEvent.AnswerRefused) events.get(events.size() - 1);
        assertThat(refused.reason()).isEqualTo("ACCESS_RESTRICTED");
        assertThat(refused.message()).isEqualTo("当前可访问知识范围内证据不足");
    }

    @Test
    void happyPathEmitsStreamingEventsWithCitations() {
        UUID ver = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, KB, ver, 0, "请假制度", "1",
                "员工请假需提前两个工作日提交申请，经直属主管审批后生效；连续请假超过五个工作日的，还需报人力资源部备案。"));
        var searchResult = new HybridSearchResult(evidence, List.of(new RankedHitView(
                KB, ver, 0, "BM25", 2.0, null, 2.0, 1, true, null)));
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.of(new ConversationView(CONV, "t", java.time.Instant.now())));
        when(recorder.start(any(), eq(CONV), eq(List.of(KB)), any(), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(USER, List.of(KB), List.of(KB), "请假")).thenReturn(searchResult);
        when(generation.generate(eq("请假"), eq(evidence), any())).thenReturn(
                new GenerationResult("根据《请假制度》[1]，员工请假需提前两个工作日提交申请",
                        List.of(new CitationView(1, UUID.randomUUID(), ver, 0, "请假制度", "[1]", "VALID")),
                        null, "deterministic", 10, 20, 5, "abc"));

        var events = service.ask(USER, new AskRequest("请假", List.of(KB), CONV));

        assertThat(events).anyMatch(e -> e instanceof QaEvent.RunStarted);
        assertThat(events).anyMatch(e -> e instanceof QaEvent.RetrievalCompleted);
        assertThat(events).anyMatch(e -> e instanceof QaEvent.AnswerDelta);
        var citations = events.stream().filter(e -> e instanceof QaEvent.CitationAvailable)
                .map(e -> (QaEvent.CitationAvailable) e).findFirst().orElseThrow();
        assertThat(citations.citations()).hasSize(1);
        assertThat(events.get(events.size() - 1)).isInstanceOf(QaEvent.AnswerCompleted.class);
        verify(recorder).complete(any());
        verify(recorder).markRetrieving(any(), argThat(hits -> hits.size() == 1));
    }

    @Test
    void retrievalFailureEmitsRunFailed() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.create(any(), any())).thenReturn(new ConversationView(UUID.randomUUID(), "t", java.time.Instant.now()));
        when(recorder.start(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(USER, List.of(KB), List.of(KB), "请假")).thenThrow(new RuntimeException("opensearch down"));
        var events = service.ask(USER, new AskRequest("请假", List.of(KB), null));
        assertThat(events.get(events.size() - 1)).isInstanceOf(QaEvent.RunFailed.class);
        verify(recorder).fail(any(), anyString());
    }

    @Test
    void createsConversationWhenNoneProvided() {
        var conversation = new ConversationView(UUID.randomUUID(), "请假", java.time.Instant.now());
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.create(USER, "请假")).thenReturn(conversation);
        when(recorder.start(any(), eq(conversation.id()), eq(List.of(KB)), any(), any())).thenReturn(UUID.randomUUID());
        when(hybridSearch.search(USER, List.of(KB), List.of(KB), "请假")).thenReturn(
                new HybridSearchResult(List.of(), List.of()));
        when(generation.generate(eq("请假"), eq(List.of()), any())).thenReturn(
                new GenerationResult(null, List.of(), RefusalReason.NO_RELEVANT_EVIDENCE,
                        "deterministic", 0, 0, 0, null));

        var events = service.ask(USER, new AskRequest("请假", List.of(KB), null));

        verify(conversations).create(USER, "请假");
        assertThat(events.get(events.size() - 1)).isInstanceOf(QaEvent.AnswerRefused.class);
    }

    @Test
    void rejectsConversationOwnedByAnotherUser() {
        when(knowledgeScope.resolve(USER, List.of(KB))).thenReturn(List.of(KB));
        when(conversations.findOwned(USER, CONV)).thenReturn(Optional.empty());
        var events = service.ask(USER, new AskRequest("请假", List.of(KB), CONV));
        assertThat(events.get(events.size() - 1)).isInstanceOf(QaEvent.RunFailed.class);
    }
}
