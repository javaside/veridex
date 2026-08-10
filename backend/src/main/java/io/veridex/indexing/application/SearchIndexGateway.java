package io.veridex.indexing.application;

import io.veridex.indexing.api.ChunkRecord;
import java.util.List;
import java.util.UUID;

public interface SearchIndexGateway {
    void createIndex(String indexName, int dimensions);
    void deleteIndex(String indexName);
    void aliasTo(String aliasName, String indexName);
    void removeAlias(String aliasName);
    void indexChunks(String indexName, UUID knowledgeBaseId, UUID documentVersionId, UUID releaseId, List<ChunkRecord> chunks);
    List<ChunkRecord> findChunksByDocumentVersion(String indexName, UUID documentVersionId);
    boolean indexExists(String indexName);
}
