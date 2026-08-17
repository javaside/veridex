package io.veridex.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.audit.api.AuditRecorder;
import io.veridex.ingestion.application.DocumentParser;
import io.veridex.ingestion.application.StructureChunker;
import io.veridex.ingestion.infrastructure.DocumentIngestionWorker;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.shared.observability.RabbitContextPropagation;
import io.veridex.shared.observability.VeridexObservability;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.json.JsonMapper;

class IngestionObservabilityTest {

    @Test
    void failedMessagePersistsFixedErrorAndRestoresRequestIdWithoutUuidTags() throws Exception {
        DocumentVersionProcessing documents = mock(DocumentVersionProcessing.class);
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID versionId = UUID.randomUUID();
        when(documents.findVersionStatus(versionId)).thenThrow(new IllegalStateException("secret detail"));
        DocumentIngestionWorker worker = worker(documents, meters);
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-veridex-telemetry-version", "1");
        properties.setHeader("x-request-id", "request-123");

        try {
            worker.onIngest(payload(versionId).getBytes(StandardCharsets.UTF_8), channel, 7L, properties);
        } finally {
            MDC.remove("requestId");
        }

        verify(documents).markFailed(versionId, "ingestion_unknown");
        verify(channel).basicReject(7L, false);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(meters.get("veridex.ingestion.run").timer().count()).isEqualTo(1);
        assertThat(meters.get("veridex.ingestion.run").timer().getId().getTags())
                .allMatch(tag -> !tag.getValue().contains(versionId.toString()));
    }

    @Test
    void malformedContextIsIgnoredAndFailureStageIsBounded() throws Exception {
        DocumentVersionProcessing documents = mock(DocumentVersionProcessing.class);
        Channel channel = mock(Channel.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        when(documents.findVersionStatus(any())).thenThrow(new IllegalStateException("secret detail"));
        DocumentIngestionWorker worker = worker(documents, meters);
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-veridex-telemetry-version", "2");
        properties.setHeader("x-request-id", "secret request id");

        worker.onIngest("not-json".getBytes(StandardCharsets.UTF_8), channel, 8L, properties);

        verify(channel).basicReject(8L, false);
        assertThat(MDC.get("requestId")).isNull();
        assertThat(meters.get("veridex.ingestion.reject").counter().count()).isEqualTo(1);
    }

    private static DocumentIngestionWorker worker(DocumentVersionProcessing documents,
                                                   SimpleMeterRegistry meters) {
        VeridexObservability observability = new VeridexObservability(meters, ObservationRegistry.create());
        RabbitContextPropagation propagation = new RabbitContextPropagation(
                mock(org.springframework.beans.factory.ObjectProvider.class),
                mock(org.springframework.beans.factory.ObjectProvider.class));
        return new DocumentIngestionWorker(documents, mock(ObjectStorage.class), mock(DocumentParser.class),
                mock(StructureChunker.class), mock(AuditRecorder.class), JsonMapper.builder().build(),
                observability, propagation, null);
    }

    private static String payload(UUID versionId) {
        return "{\"documentVersionId\":\"" + versionId
                + "\",\"knowledgeBaseId\":\"" + UUID.randomUUID()
                + "\",\"objectKey\":\"kb/doc.md\",\"filename\":\"doc.md\",\"contentType\":\"text/markdown\"}";
    }
}
