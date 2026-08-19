package io.veridex.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.generation.application.CitationValidator;
import io.veridex.generation.application.GenerationServiceImpl;
import io.veridex.generation.application.RefusalPolicy;
import io.veridex.generation.infrastructure.DeterministicChatModel;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.observability.VeridexObservability;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SensitiveOutputRegressionTest {

    @Mock RefusalPolicy refusalPolicy;
    @Mock CitationValidator citationValidator;
    @Mock DocumentVersionQuery documentVersions;

    @Test
    void telemetryNeverRecordsQuestionEvidenceOrCredentials() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var observability = new VeridexObservability(meters, ObservationRegistry.create());
        var model = new DeterministicChatModel();
        var chatProperties = new io.veridex.generation.infrastructure.ChatProperties("deterministic",
                java.time.Duration.ofSeconds(60),
                new io.veridex.generation.infrastructure.ChatProperties.Ollama("http://localhost:11434", "qwen3:8b"));
        GenerationServiceImpl service = new GenerationServiceImpl(model, refusalPolicy, citationValidator,
                documentVersions, observability, chatProperties);

        String question = "SENSITIVE_QUESTION_5C";
        String chunkText = "SENSITIVE_CHUNK_5C";
        String credential = "SENSITIVE_PASSWORD_5C";
        UUID versionId = UUID.randomUUID();
        var evidence = List.of(new EvidencePiece(1, UUID.randomUUID(), versionId, 0, "SENSITIVE_TITLE", "1", chunkText));
        when(refusalPolicy.evaluate(any(), anyInt())).thenReturn(null);
        when(citationValidator.validate(any(), any(), any())).thenReturn(List.of());

        service.generate(question + credential, evidence, List.of());

        var metersFound = meters.find("veridex.generation.model").timers();
        assertThat(metersFound).isNotEmpty();
        for (var timer : metersFound) {
            timer.getId().getTags().forEach(tag ->
                    assertThat(tag.getValue())
                            .doesNotContain("SENSITIVE")
                            .doesNotContain(versionId.toString()));
        }
    }
}
