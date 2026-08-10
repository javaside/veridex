package io.veridex.indexing.api;

import java.util.List;
import java.util.UUID;

public interface ChunkIndexer {
    void index(UUID knowledgeBaseId, UUID documentVersionId, List<ChunkRecord> chunks,
               String indexName, UUID releaseId);
}
