package io.veridex.generation.application;

import io.veridex.conversation.api.MessageRecord;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationParameters;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.GenerationService;
import io.veridex.generation.infrastructure.DeterministicChatModel;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.BoundedModelTags;
import io.veridex.shared.observability.ObservationName;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

@Service
public class GenerationServiceImpl implements GenerationService {

    private static final String DEFAULT_SYSTEM_TEMPLATE =
            "你是企业制度问答助手。只允许使用以下证据回答，不得使用模型通用知识补全。\n";
    private static final GenerationParameters DEFAULT_PARAMETERS =
            new GenerationParameters(50, DEFAULT_SYSTEM_TEMPLATE, "deterministic");

    private final DeterministicChatModel model;
    private final RefusalPolicy refusalPolicy;
    private final CitationValidator citationValidator;
    private final DocumentVersionQuery documentVersions;
    private final VeridexObservability observability;
    private final BoundedModelTags modelTags;

    public GenerationServiceImpl(DeterministicChatModel model, RefusalPolicy refusalPolicy,
                                 CitationValidator citationValidator, DocumentVersionQuery documentVersions,
                                 VeridexObservability observability) {
        this.model = model;
        this.refusalPolicy = refusalPolicy;
        this.citationValidator = citationValidator;
        this.documentVersions = documentVersions;
        this.observability = observability;
        this.modelTags = new BoundedModelTags(java.util.Set.of("deterministic"));
    }

    @Override
    public GenerationResult generate(String question, List<EvidencePiece> evidence, List<MessageRecord> history) {
        return generate(question, evidence, history, DEFAULT_PARAMETERS);
    }

    @Override
    public GenerationResult generate(String question, List<EvidencePiece> evidence,
                                     List<MessageRecord> history, GenerationParameters parameters) {
        RefusalReason refusal = refusalPolicy.evaluate(evidence, parameters.minEvidenceChars());
        if (refusal != null) {
            return new GenerationResult(null, List.of(), refusal, parameters.model(), 0, 0, 0, null);
        }

        String system = buildSystemPrompt(evidence, parameters.systemTemplate());
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        for (MessageRecord record : history) {
            messages.add(record.role().equals("USER")
                    ? new UserMessage(record.content())
                    : new AssistantMessage(record.content()));
        }
        messages.add(new UserMessage(question));

        var boundedTags = modelTags.resolve("deterministic", parameters.model());
        ChatResponse response;
        long start = System.nanoTime();
        var observation = observability.start(ObservationName.GENERATION_MODEL, boundedTags.tags());
        try {
            response = model.call(new Prompt(messages));
            observation.success(TelemetryTag.generationOutcome(TelemetryOutcome.Generation.SUCCESS));
        } catch (RuntimeException e) {
            observation.failure(TelemetryErrorCode.classify(e));
            throw e;
        } finally {
            observation.close();
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        String answer = response.getResult().getOutput().getText();
        int outputTokens = answer.length() / 4;
        int inputTokens = (system.length() + question.length()) / 4;

        List<CitationView> citations = citationValidator.validate(answer, evidence,
                documentIdByVersionId(evidence));
        String contextHash = Integer.toHexString(evidence.hashCode());

        return new GenerationResult(answer, citations, null, parameters.model(),
                inputTokens, outputTokens, durationMs, contextHash);
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
