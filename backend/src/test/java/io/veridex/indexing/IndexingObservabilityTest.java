package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.indexing.api.ChunkIndexer;
import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.api.IndexReleaseManager;
import io.veridex.indexing.application.KnowledgeBasePublishService;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import io.veridex.shared.observability.VeridexObservability;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IndexingObservabilityTest {

    @Test
    void failedPublishUsesFixedErrorCodeAndKeepsReleaseIdentifiersOutOfTags() {
        DocumentVersionProcessing documents = mock(DocumentVersionProcessing.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        ChunkIndexer indexer = mock(ChunkIndexer.class);
        IndexReleaseManager releases = mock(IndexReleaseManager.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        when(documents.listReadyVersions(knowledgeBaseId)).thenReturn(List.of(
                new DocumentVersionProcessing.ReadyVersion(UUID.randomUUID(), "kb/doc", 1)));
        when(releases.createDraft(any(), any())).thenReturn(new DraftRelease(releaseId, "idx", "alias"));
        org.mockito.Mockito.doThrow(new IllegalStateException("secret index detail"))
                .when(indexer).index(any(), any(), any(), any(), any());

        KnowledgeBasePublishService service = new KnowledgeBasePublishService(documents, storage, indexer, releases,
                JsonMapper.builder().build(), new VeridexObservability(meters, ObservationRegistry.create()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.publish(knowledgeBaseId))
                .isInstanceOf(RuntimeException.class);
        verify(releases).discardDraft(releaseId);
        var timer = meters.get("veridex.indexing.publish").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags()).allMatch(tag -> !tag.getValue().contains(knowledgeBaseId.toString()));
        assertThat(timer.getId().getTags()).allMatch(tag -> !tag.getValue().contains(releaseId.toString()));
    }
}
