package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.conversation.api.MessageRecord;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.application.CitationValidator;
import io.veridex.generation.application.GenerationServiceImpl;
import io.veridex.generation.application.RefusalPolicy;
import io.veridex.generation.infrastructure.DeterministicChatModel;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.VeridexObservability;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

@ExtendWith(MockitoExtension.class)
class GenerationServiceImplTest {

    @Mock DeterministicChatModel model;
    @Mock RefusalPolicy refusalPolicy;
    @Mock CitationValidator citationValidator;
    @Mock DocumentVersionQuery documentVersions;
    @Spy VeridexObservability observability = new VeridexObservability(new SimpleMeterRegistry(), ObservationRegistry.create());
    @InjectMocks GenerationServiceImpl service;

    @Test
    void refusesWhenPolicySaysSo() {
        when(refusalPolicy.evaluate(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(RefusalReason.NO_RELEVANT_EVIDENCE);
        var result = service.generate("q", List.of(), List.of());
        assertThat(result.refusalReason()).isEqualTo(RefusalReason.NO_RELEVANT_EVIDENCE);
        assertThat(result.answer()).isNull();
        assertThat(result.promptMessages()).isEmpty();
        verify(model, never()).call(any(Prompt.class));
    }

    @Test
    void generatesAndValidatesCitations() {
        UUID versionId = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), versionId, 0, "t", "1",
                "员工请假需提前两个工作日提交申请。"));
        when(refusalPolicy.evaluate(eq(evidence), org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("根据《t》[1]，员工请假需提前两个工作日提交申请")))));
        when(citationValidator.validate(any(), eq(evidence), any())).thenReturn(List.of(
                new CitationView(1, UUID.randomUUID(), versionId, 0, "t", "[1]", "VALID")));

        var result = service.generate("请假", evidence, List.of());

        assertThat(result.answer()).contains("[1]");
        assertThat(result.promptMessages()).extracting(m -> m.role()).containsExactly("system", "user");
        assertThat(result.promptMessages()).extracting(m -> m.content()).contains("请假");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).validationStatus()).isEqualTo("VALID");
        assertThat(result.citations().get(0).documentId()).isNotNull();
        assertThat(result.model()).isEqualTo("deterministic");
    }

    @Test
    void recordsModelFailureWithBoundedTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GenerationServiceImpl instrumented = new GenerationServiceImpl(model, refusalPolicy, citationValidator,
                documentVersions, new VeridexObservability(meters, ObservationRegistry.create()));
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t", "1", "text"));
        when(refusalPolicy.evaluate(eq(evidence), org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("SENSITIVE_MODEL_FAILURE"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> instrumented.generate("SENSITIVE_QUESTION", evidence, List.of()))
                .isInstanceOf(RuntimeException.class);

        assertThat(meters.find("veridex.generation.model").tag("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(meters.find("veridex.generation.model").timer().getId().getTags())
                .allMatch(tag -> !tag.getValue().contains("SENSITIVE") && !tag.getValue().contains(evidence.getFirst().documentVersionId().toString()));
    }

    @Test
    void generatesWithHistoryInjected() {
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "t", "1",
                "年假最长不超过十五个工作日。"));
        when(refusalPolicy.evaluate(eq(evidence), org.mockito.ArgumentMatchers.anyInt())).thenReturn(null);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("根据《t》[1]，年假最长不超过十五个工作日")))));
        when(citationValidator.validate(any(), eq(evidence), any())).thenReturn(List.of());

        var history = List.of(
                new MessageRecord(UUID.randomUUID(), "USER", "年假多少天", null),
                new MessageRecord(UUID.randomUUID(), "ASSISTANT", "最长十五个工作日", null));
        var result = service.generate("最长多久", evidence, history);

        assertThat(result.answer()).contains("十五个工作日");
        assertThat(result.inputTokens()).isPositive();
        assertThat(result.outputTokens()).isPositive();
    }
}
