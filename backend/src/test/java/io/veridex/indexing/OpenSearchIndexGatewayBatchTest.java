package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.infrastructure.OpenSearchIndexGateway;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

@ExtendWith(MockitoExtension.class)
class OpenSearchIndexGatewayBatchTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) OpenSearchClient client;
    @Mock EmbeddingModel embeddings;

    private OpenSearchIndexGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new OpenSearchIndexGateway(client, embeddings);
    }

    @Test
    void embedsInBulkBatchesInsteadOfPerChunk() {
        List<ChunkRecord> chunks = IntStream.range(0, 120)
                .mapToObj(i -> new ChunkRecord(i, "chunk-" + i, "title", "1"))
                .toList();

        // Distinct vector per instruction: index within that request.
        when(embeddings.call(any(EmbeddingRequest.class))).thenAnswer(inv -> {
            var request = inv.<EmbeddingRequest>getArgument(0);
            List<Embedding> results = IntStream.range(0, request.getInstructions().size())
                    .mapToObj(i -> new Embedding(new float[]{i}, 0))
                    .toList();
            return new EmbeddingResponse(results);
        });

        gateway.indexChunks("idx", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), chunks);

        var captor = ArgumentCaptor.forClass(EmbeddingRequest.class);
        verify(embeddings, times(3)).call(captor.capture());
        verify(embeddings, never()).embed(anyString());

        var requests = captor.getAllValues();
        assertThat(requests.stream().map(r -> r.getInstructions().size()).toList())
                .containsExactly(50, 50, 20);

        // Instruction TEXTS in the same order as chunk texts pins the partition + ordering
        // (the per-chunk vector is the same index within each request, so text order == vector order).
        List<String> embeddedTexts = requests.stream()
                .flatMap(r -> r.getInstructions().stream())
                .toList();
        assertThat(embeddedTexts).containsExactlyElementsOf(
                chunks.stream().map(ChunkRecord::text).toList());
    }

    @Test
    void throwsWhenEmbeddingReturnsFewerVectorsThanChunks() {
        when(embeddings.call(any(EmbeddingRequest.class)))
                .thenReturn(new EmbeddingResponse(List.of(new Embedding(new float[]{1.0f}, 0))));

        List<ChunkRecord> chunks = IntStream.range(0, 3)
                .mapToObj(i -> new ChunkRecord(i, "chunk-" + i, "title", "1"))
                .toList();

        assertThatThrownBy(() -> gateway.indexChunks("idx", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), chunks))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vectors");
    }
}
