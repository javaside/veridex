package io.veridex.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationEvent;
import io.veridex.generation.application.CitationValidator;
import io.veridex.generation.application.GenerationServiceImpl;
import io.veridex.generation.api.InvalidCitationException;
import io.veridex.generation.api.ModelEmptyException;
import io.veridex.generation.api.ModelTimeoutException;
import io.veridex.generation.application.RefusalPolicy;
import io.veridex.generation.infrastructure.ChatProperties;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.VeridexObservability;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * 流式生成（设计 §4.2/§5/§6）：delta 顺序、空片段过滤、usage 优先精确值、
 * 引用终检失败不发 Completed、超时 MODEL_TIMEOUT、空响应 MODEL_ERROR、拒答不发模型调用。
 */
@ExtendWith(MockitoExtension.class)
class GenerationStreamingTest {

    @Mock ChatModel model;
    @Mock RefusalPolicy refusalPolicy;
    @Mock CitationValidator citationValidator;
    @Mock DocumentVersionQuery documentVersions;
    ChatProperties chatProperties = new ChatProperties("deterministic", Duration.ofSeconds(60),
            new ChatProperties.Ollama("http://localhost:11434", "qwen3:8b"));

    private GenerationServiceImpl service() {
        return new GenerationServiceImpl(model, refusalPolicy, citationValidator, documentVersions,
                new VeridexObservability(new SimpleMeterRegistry(), ObservationRegistry.create()), chatProperties);
    }

    private static EvidencePiece evidence() {
        return new EvidencePiece(1, UUID.randomUUID(), UUID.randomUUID(), 0, "请假制度", "1",
                "员工请假需提前两个工作日向直属主管提交书面申请，经审批后生效。");
    }

    @Test
    void streamsDeltasInOrderThenCompletes() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("根据"))),
                        metadataWithUsage(1, 1)),
                new ChatResponse(List.of(new Generation(new AssistantMessage("《请假制度》[1]"))),
                        metadataWithUsage(1, 3))));

        StepVerifier.create(service().stream("请假", List.of(evidence()), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta d && d.text().equals("根据"))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta d && d.text().equals("《请假制度》[1]"))
                .expectNextMatches(e -> e instanceof GenerationEvent.Completed c
                        && c.result().answer().equals("根据《请假制度》[1]")
                        && c.result().firstTokenLatencyMs() >= 0
                        && !c.result().usageEstimated())
                .verifyComplete();
    }

    @Test
    void filtersBlankFragments() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("  ")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("答案"))))));

        StepVerifier.create(service().stream("q", List.of(evidence()), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta d && d.text().equals("答案"))
                .expectNextMatches(e -> e instanceof GenerationEvent.Completed)
                .verifyComplete();
    }

    @Test
    void estimatesUsageWhenProviderOmitsMetadata() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of());
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("答案[1]"))))));

        StepVerifier.create(service().stream("q", List.of(evidence()), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta d && d.text().equals("答案[1]"))
                .expectNextMatches(e -> e instanceof GenerationEvent.Completed c && c.result().usageEstimated())
                .verifyComplete();
    }

    @Test
    void invalidCitationEmitsErrorNotCompleted() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of(
                new CitationView(9, null, UUID.randomUUID(), 0, "t", "[9]", "INVALID")));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("没有引用[9]"))))));

        StepVerifier.create(service().stream("q", List.of(evidence()), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Delta)
                .expectErrorMatches(t -> t instanceof InvalidCitationException)
                .verify();
    }

    @Test
    void timeoutMapsToModelTimeoutError() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.never());
        ChatProperties shortTimeout = new ChatProperties("deterministic", Duration.ofMillis(50),
                new ChatProperties.Ollama("http://localhost:11434", "qwen3:8b"));
        GenerationServiceImpl timed = new GenerationServiceImpl(model, refusalPolicy, citationValidator,
                documentVersions, new VeridexObservability(new SimpleMeterRegistry(), ObservationRegistry.create()),
                shortTimeout);

        StepVerifier.create(timed.stream("q", List.of(evidence()), List.of()))
                .expectErrorMatches(t -> t instanceof ModelTimeoutException)
                .verify();
    }

    @Test
    void emptyResponseMapsToModelError() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(null);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.empty());

        StepVerifier.create(service().stream("q", List.of(evidence()), List.of()))
                .expectErrorMatches(t -> t instanceof ModelEmptyException)
                .verify();
    }

    @Test
    void refusalEmitsRefusedWithoutCallingModel() {
        when(refusalPolicy.evaluate(any(), eq(50))).thenReturn(RefusalReason.NO_RELEVANT_EVIDENCE);

        StepVerifier.create(service().stream("q", List.of(), List.of()))
                .expectNextMatches(e -> e instanceof GenerationEvent.Refused r
                        && r.result().refusalReason() == RefusalReason.NO_RELEVANT_EVIDENCE)
                .verifyComplete();
    }

    private static org.springframework.ai.chat.metadata.ChatResponseMetadata metadataWithUsage(int prompt, int completion) {
        // spring-ai 2.0.0：ChatResponseMetadata 在 chat.metadata 包；DefaultUsage 只有构造器、无 builder
        return org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .usage(new org.springframework.ai.chat.metadata.DefaultUsage(prompt, completion, prompt + completion))
                .build();
    }
}
