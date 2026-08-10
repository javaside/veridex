package io.veridex.indexing.infrastructure;

import io.veridex.indexing.api.ChunkIndexer;
import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.application.SearchIndexGateway;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReleaseChunkIndexer implements ChunkIndexer {

    private final SearchIndexGateway gateway;

    public ReleaseChunkIndexer(SearchIndexGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void index(UUID knowledgeBaseId, UUID documentVersionId, List<ChunkRecord> chunks,
                      String indexName, UUID releaseId) {
        gateway.indexChunks(indexName, knowledgeBaseId, documentVersionId, releaseId, chunks);
    }
}
