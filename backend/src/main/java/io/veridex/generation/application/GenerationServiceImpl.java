package io.veridex.generation.application;

import io.veridex.conversation.api.MessageRecord;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationErrorCodes;
import io.veridex.generation.api.GenerationEvent;
import io.veridex.generation.api.GenerationParameters;
import io.veridex.generation.api.InvalidCitationException;
import io.veridex.generation.api.ModelEmptyException;
import io.veridex.generation.api.ModelTimeoutException;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.GenerationService;
import io.veridex.generation.api.PromptMessageView;
import io.veridex.generation.infrastructure.ChatProperties;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.BoundedModelTags;
import io.veridex.shared.observability.MetricName;
import io.veridex.shared.observability.ObservationName;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 生成编排（设计 §4.2/§4.5/§5/§6）：同步与流式共享拒答、prompt、引用终检、
 * usage 提取与观测逻辑；模型异常/超时/空响应/引用失败均无 deterministic 回退。
 */
@Service
public class GenerationServiceImpl implements GenerationService {

    private static final String DEFAULT_SYSTEM_TEMPLATE =
            "你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n"
            + "每个证据块以 [EVIDENCE 编号|标题|正文] 给出。回答时引用证据必须使用 [编号] 格式"
            + "（例如 [1]、[2]，编号对应证据块开头的编号），不要写 [EVIDENCE ...] 字样。\n";
    private static final GenerationParameters DEFAULT_PARAMETERS =
            new GenerationParameters(50, DEFAULT_SYSTEM_TEMPLATE, "deterministic");

    private final ChatModel model;
    private final ChatProperties chatProperties;
    private final RefusalPolicy refusalPolicy;
    private final CitationValidator citationValidator;
    private final DocumentVersionQuery documentVersions;
    private final VeridexObservability observability;
    private final BoundedModelTags modelTags;

    public GenerationServiceImpl(ChatModel model, RefusalPolicy refusalPolicy,
                                 CitationValidator citationValidator, DocumentVersionQuery documentVersions,
                                 VeridexObservability observability, ChatProperties chatProperties) {
        this.model = model;
        this.refusalPolicy = refusalPolicy;
        this.citationValidator = citationValidator;
        this.documentVersions = documentVersions;
        this.observability = observability;
        this.chatProperties = chatProperties;
        this.modelTags = new BoundedModelTags(java.util.Set.of("deterministic", "ollama", "deepseek"));
    }

    // ---------- 同步入口（评测继续使用） ----------

    @Override
    public GenerationResult generate(String question, List<EvidencePiece> evidence, List<MessageRecord> history) {
        return generate(question, evidence, history, DEFAULT_PARAMETERS);
    }

    @Override
    public GenerationResult generate(String question, List<EvidencePiece> evidence,
                                     List<MessageRecord> history, GenerationParameters parameters) {
        RefusalReason refusal = refusalPolicy.evaluate(evidence, parameters.minEvidenceChars());
        if (refusal != null) {
            GenerationResult result = refusedResult(parameters, refusal);
            observability.increment(MetricName.GENERATION_OUTCOME,
                    TelemetryTag.generationOutcome(TelemetryOutcome.Generation.REFUSED), providerTag());
            return result;
        }

        String system = buildSystemPrompt(evidence, parameters.systemTemplate());
        Prompt prompt = buildPrompt(system, history, question);
        List<PromptMessageView> promptMessages = promptViews(prompt);

        long start = System.nanoTime();
        var observation = observability.start(ObservationName.GENERATION_MODEL,
                modelTags.resolve(provider(), modelName()).tags());
        ChatResponse response;
        try {
            response = model.call(prompt);
            observation.success(TelemetryTag.generationOutcome(TelemetryOutcome.Generation.SUCCESS));
        } catch (RuntimeException e) {
            observation.failure(GenerationErrorCodes.classify(e));
            throw e;
        } finally {
            observation.close();
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String answer = response.getResult().getOutput().getText();
        Usage usage = extractUsage(response, system.length() + question.length(), answer);
        return finalizeResult(answer, evidence, provider(), modelName(), usage, durationMs, 0, promptMessages);
    }

    // ---------- 流式入口（在线问答） ----------

    @Override
    public Flux<GenerationEvent> stream(String question, List<EvidencePiece> evidence, List<MessageRecord> history) {
        RefusalReason refusal = refusalPolicy.evaluate(evidence, DEFAULT_PARAMETERS.minEvidenceChars());
        if (refusal != null) {
            GenerationResult result = refusedResult(DEFAULT_PARAMETERS, refusal);
            observability.increment(MetricName.GENERATION_OUTCOME,
                    TelemetryTag.generationOutcome(TelemetryOutcome.Generation.REFUSED), providerTag());
            return Flux.just(new GenerationEvent.Refused(result));
        }

        String system = buildSystemPrompt(evidence, DEFAULT_PARAMETERS.systemTemplate());
        Prompt prompt = buildPrompt(system, history, question);
        List<PromptMessageView> promptMessages = promptViews(prompt);
        long start = System.nanoTime();
        AtomicLong firstTokenLatencyMs = new AtomicLong(-1);
        StringBuilder buffer = new StringBuilder();
        AtomicReference<ChatResponse> lastResponse = new AtomicReference<>();
        var observation = observability.start(ObservationName.GENERATION_MODEL,
                modelTags.resolve(provider(), modelName()).tags());

        Flux<ChatResponse> upstream = model.stream(prompt)
                .timeout(chatProperties.timeout(),
                        Flux.error(new ModelTimeoutException("model timeout")))
                .doOnNext(response -> {
                    lastResponse.set(response);
                    var result = response.getResult();
                    String text = result == null || result.getOutput() == null ? null : result.getOutput().getText();
                    if (text != null && !text.isEmpty()) {
                        if (firstTokenLatencyMs.get() < 0) {
                            firstTokenLatencyMs.set((System.nanoTime() - start) / 1_000_000);
                        }
                        buffer.append(text);
                    }
                });

        return upstream
                // reasoning 模型（如 deepseek-v4-pro）流式时先输出 reasoning_content，
                // 此时 content 为 null；mapNotNull 过滤掉这些空 chunk，避免 Flux.map 对 null 抛 NPE。
                .mapNotNull(response -> {
                    var result = response.getResult();
                    return result == null || result.getOutput() == null ? null : result.getOutput().getText();
                })
                .filter(text -> !text.isBlank())
                .map(text -> (GenerationEvent) new GenerationEvent.Delta(text))
                .concatWith(Flux.defer(() -> {
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                    long firstToken = firstTokenLatencyMs.get() < 0 ? elapsedMs : firstTokenLatencyMs.get();
                    String answer = buffer.toString();
                    if (answer.isBlank()) {
                        observation.failure(TelemetryErrorCode.MODEL_ERROR);
                        throw new ModelEmptyException("empty model response");
                    }
                    Usage usage = extractUsage(lastResponse.get(), system.length() + question.length(), answer);
                    GenerationResult result = finalizeResult(answer, evidence, provider(), modelName(),
                            usage, elapsedMs, firstToken, promptMessages);
                    observation.success(TelemetryTag.generationOutcome(TelemetryOutcome.Generation.SUCCESS));
                    observability.increment(MetricName.GENERATION_OUTCOME,
                            TelemetryTag.generationOutcome(TelemetryOutcome.Generation.SUCCESS), providerTag());
                    observability.record(MetricName.GENERATION_FIRST_TOKEN, firstToken, providerTag());
                    return Flux.just(new GenerationEvent.Completed(result));
                }))
                .doOnError(e -> observation.failure(GenerationErrorCodes.classify(e)));
    }

    // ---------- 共享私有逻辑 ----------

    private GenerationResult refusedResult(GenerationParameters parameters, RefusalReason refusal) {
        return new GenerationResult(null, List.of(), refusal, provider(), modelName(), 0, 0, 0, 0, true, null,
                List.of());
    }

    private GenerationResult finalizeResult(String answer, List<EvidencePiece> evidence, String provider,
                                            String model, Usage usage, long durationMs, long firstTokenLatencyMs,
                                            List<PromptMessageView> promptMessages) {
        List<CitationView> citations = citationValidator.validate(answer, evidence,
                documentIdByVersionId(evidence));
        boolean validCitations = citations.stream().allMatch(c -> "VALID".equals(c.validationStatus()));
        if (!validCitations) {
            throw new InvalidCitationException("answer failed citation final check");
        }
        String contextHash = Integer.toHexString(evidence.hashCode());
        return new GenerationResult(answer, citations, null, provider, model, usage.inputTokens(),
                usage.outputTokens(), durationMs, firstTokenLatencyMs, usage.estimated(), contextHash, promptMessages);
    }

    private String provider() {
        return chatProperties.provider();
    }

    private String modelName() {
        return switch (chatProperties.provider()) {
            case "ollama" -> chatProperties.ollama().model();
            case "deepseek" -> chatProperties.deepseek().model();
            default -> "deterministic";
        };
    }

    private TelemetryTag providerTag() {
        // 从受控 BoundedModelTags.resolve 推导（tags()[0] 为 provider 标签），避免绕过 provider 白名单
        return modelTags.resolve(provider(), modelName()).tags()[0];
    }

    private record Usage(int inputTokens, int outputTokens, boolean estimated) {
    }

    private Usage extractUsage(ChatResponse response, int inputChars, String answer) {
        var metadata = response != null && response.getMetadata() != null ? response.getMetadata() : null;
        var usage = metadata != null ? metadata.getUsage() : null;
        if (usage != null && usage.getPromptTokens() != null && usage.getCompletionTokens() != null
                && usage.getPromptTokens() > 0 && usage.getCompletionTokens() > 0) {
            return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), false);
        }
        return new Usage(Math.max(1, inputChars / 4), Math.max(1, answer.length() / 4), true);
    }

    private Prompt buildPrompt(String system, List<MessageRecord> history, String question) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        for (MessageRecord record : history) {
            messages.add(record.role().equals("USER")
                    ? new UserMessage(record.content())
                    : new AssistantMessage(record.content()));
        }
        messages.add(new UserMessage(question));
        return new Prompt(messages);
    }

    private List<PromptMessageView> promptViews(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(message -> new PromptMessageView(message.getMessageType().getValue(), message.getText()))
                .toList();
    }

    private Map<UUID, UUID> documentIdByVersionId(List<EvidencePiece> evidence) {
        List<UUID> versionIds = evidence.stream().map(EvidencePiece::documentVersionId).distinct().toList();
        return documentVersions.findDocumentIdByVersionIds(versionIds);
    }

    private String buildSystemPrompt(List<EvidencePiece> evidence, String systemTemplate) {
        StringBuilder sb = new StringBuilder(systemTemplate);
        for (EvidencePiece e : evidence) {
            sb.append("[EVIDENCE ").append(e.citationIndex()).append("|").append(e.title())
                    .append("|").append(e.text()).append("]\n");
        }
        return sb.toString();
    }
}
