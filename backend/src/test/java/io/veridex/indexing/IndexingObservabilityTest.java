package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.indexing.api.ChunkIndexer;
import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.api.IndexReleaseManager;
import io.veridex.indexing.application.KnowledgeBasePublishService;
import io.veridex.indexing.application.KnowledgeBasePublishService.PublishTask;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.DocumentVersionProcessing.ReadyVersion;
import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.shared.observability.VeridexObservability;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IndexingObservabilityTest {

    @Test
    void failedPublishUsesFixedErrorCodeAndKeepsReleaseIdentifiersOutOfTags() {
        ObjectStorage storage = mock(ObjectStorage.class);
        ChunkIndexer indexer = mock(ChunkIndexer.class);
        IndexReleaseManager releases = mock(IndexReleaseManager.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        ReadyVersion ready = new ReadyVersion(UUID.randomUUID(), "kb/doc", 1);
        DraftRelease draft = new DraftRelease(releaseId, "idx", "alias");

        when(storage.get(any())).thenReturn(new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)));
        doThrow(new IllegalStateException("secret index detail"))
                .when(indexer).index(any(), any(), any(), any(), any());

        KnowledgeBasePublishService service = new KnowledgeBasePublishService(
                mock(DocumentVersionProcessing.class), storage, indexer, releases,
                JsonMapper.builder().build(), new VeridexObservability(meters, ObservationRegistry.create()));

        PublishTask task = new PublishTask(knowledgeBaseId, draft, List.of(ready));

        assertThatThrownBy(() -> service.runPublish(task))
                .isInstanceOf(RuntimeException.class);
        verify(releases).discardDraft(releaseId);
        var timer = meters.get("veridex.indexing.publish").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.getId().getTags()).allMatch(tag -> !tag.getValue().contains(knowledgeBaseId.toString()));
        assertThat(timer.getId().getTags()).allMatch(tag -> !tag.getValue().contains(releaseId.toString()));
    }
}
