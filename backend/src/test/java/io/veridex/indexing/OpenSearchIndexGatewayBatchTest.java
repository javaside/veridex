package io.veridex.indexing;

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
        when(embeddings.call(any(EmbeddingRequest.class))).thenAnswer(inv -> {
            var request = inv.<EmbeddingRequest>getArgument(0);
            List<Embedding> results = request.getInstructions().stream()
                    .map(text -> new Embedding(new float[]{1.0f}, 0))
                    .toList();
            return new EmbeddingResponse(results);
        });
    }

    @Test
    void embedsInBulkBatchesInsteadOfPerChunk() {
        List<ChunkRecord> chunks = IntStream.range(0, 120)
                .mapToObj(i -> new ChunkRecord(i, "chunk-" + i, "title", "1"))
                .toList();

        gateway.indexChunks("idx", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), chunks);

        verify(embeddings, times(3)).call(any(EmbeddingRequest.class)); // 50 + 50 + 20
        verify(embeddings, never()).embed(anyString());
    }
}
