package io.veridex.indexing.infrastructure;

import io.veridex.indexing.api.ChunkRecord;
import io.veridex.indexing.application.SearchIndexGateway;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.stereotype.Component;

@Component
public class OpenSearchIndexGateway implements SearchIndexGateway {

    private static final int BULK_BATCH = 50;

    private final OpenSearchClient client;
    private final EmbeddingModel embeddings;

    public OpenSearchIndexGateway(OpenSearchClient client, EmbeddingModel embeddings) {
        this.client = client;
        this.embeddings = embeddings;
    }

    @Override
    public void createIndex(String indexName, int dimensions) {
        try {
            if (indexExists(indexName)) {
                return; // 幂等：已存在则跳过
            }
            IndexSettings settings = new IndexSettings.Builder()
                    .numberOfShards(1).numberOfReplicas(0)
                    // kNN 查询必需：启用后 knn_vector 字段才会构建 ANN 索引，否则查询报
                    // "Field ... is not built for ANN search"（OpenSearch 3.x 默认关闭）
                    .knn(true)
                    .build();
            TypeMapping mapping = new TypeMapping.Builder()
                    .properties(Map.of(
                            "text", Property.of(p -> p.text(t -> t)),
                            "embedding", Property.of(p -> p.knnVector(k -> k.dimension(dimensions))),
                            "document_version_id", Property.of(p -> p.keyword(k -> k)),
                            "knowledge_base_id", Property.of(p -> p.keyword(k -> k)),
                            "release_id", Property.of(p -> p.keyword(k -> k)),
                            "chunk_index", Property.of(p -> p.integer(i -> i)),
                            "structure_path", Property.of(p -> p.keyword(k -> k)),
                            "title", Property.of(p -> p.keyword(k -> k))))
                    .build();
            client.indices().create(c -> c.index(indexName).settings(settings).mappings(mapping));
        } catch (IOException e) {
            throw new RuntimeException("failed to create index " + indexName, e);
        }
    }

    @Override
    public void indexChunks(String indexName, UUID knowledgeBaseId, UUID documentVersionId,
                            UUID releaseId, List<ChunkRecord> chunks) {
        for (int start = 0; start < chunks.size(); start += BULK_BATCH) {
            List<ChunkRecord> batch = chunks.subList(start, Math.min(start + BULK_BATCH, chunks.size()));
            var request = new org.opensearch.client.opensearch.core.BulkRequest.Builder();
            List<float[]> vectors = embedBatch(batch.stream().map(ChunkRecord::text).toList());
            for (int i = 0; i < batch.size(); i++) {
                ChunkRecord chunk = batch.get(i);
                float[] vector = vectors.get(i);
                // _id 固定为 documentVersionId:chunkIndex → 重复投递幂等（覆盖写）
                String docId = documentVersionId + ":" + chunk.index();
                request.operations(op -> op.index(idx -> idx
                        .index(indexName).id(docId)
                        .document(Map.of(
                                "text", chunk.text(),
                                "embedding", vector,
                                "document_version_id", documentVersionId.toString(),
                                "knowledge_base_id", knowledgeBaseId.toString(),
                                "release_id", releaseId.toString(),
                                "chunk_index", chunk.index(),
                                "structure_path", chunk.structurePath(),
                                "title", chunk.title()))));
            }
            try {
                var response = client.bulk(request.build());
                if (response.errors()) {
                    throw new IllegalStateException("bulk index reported errors for " + indexName);
                }
            } catch (IOException e) {
                throw new RuntimeException("bulk index failed for " + indexName, e);
            }
        }
        try {
            // 显式 refresh：保证随后查询立即可见（OpenSearch 默认近实时 1s）
            client.indices().refresh(r -> r.index(indexName));
        } catch (IOException e) {
            throw new RuntimeException("refresh failed for " + indexName, e);
        }
    }

    private List<float[]> embedBatch(List<String> texts) {
        var response = embeddings.call(new EmbeddingRequest(texts, EmbeddingOptions.builder().build()));
        return response.getResults().stream().map(Embedding::getOutput).toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ChunkRecord> findChunksByDocumentVersion(String indexName, UUID documentVersionId) {
        if (!indexExists(indexName)) {
            return List.of(); // 索引不存在（如已 offline 移除 alias）视为空结果
        }
        try {
            var response = client.search(SearchRequest.of(s -> s
                    .index(indexName)
                    .query(q -> q.term(t -> t.field("document_version_id")
                            .value(org.opensearch.client.opensearch._types.FieldValue.of(documentVersionId.toString()))))
                    .size(10_000)), Map.class);
            List<ChunkRecord> out = new ArrayList<>();
            for (var hit : response.hits().hits()) {
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null) {
                    continue;
                }
                out.add(new ChunkRecord(((Number) src.get("chunk_index")).intValue(),
                        String.valueOf(src.get("text")),
                        String.valueOf(src.get("title")),
                        String.valueOf(src.get("structure_path"))));
            }
            out.sort(Comparator.comparingInt(ChunkRecord::index));
            return out;
        } catch (IOException e) {
            throw new RuntimeException("search failed on " + indexName, e);
        }
    }

    @Override
    public void aliasTo(String aliasName, String indexName) {
        try {
            var request = new org.opensearch.client.opensearch.indices.UpdateAliasesRequest.Builder();
            if (indexExists(aliasName)) {
                request.actions(a -> a.remove(r -> r.index("*").alias(aliasName)));
            }
            request.actions(a -> a.add(add -> add.index(indexName).alias(aliasName)));
            client.indices().updateAliases(request.build());
        } catch (IOException e) {
            throw new RuntimeException("alias switch failed for " + aliasName, e);
        }
    }

    @Override
    public void removeAlias(String aliasName) {
        try {
            if (indexExists(aliasName)) {
                client.indices().deleteAlias(d -> d.index("*").name(aliasName));
            }
        } catch (IOException e) {
            throw new RuntimeException("alias removal failed for " + aliasName, e);
        }
    }

    @Override
    public boolean indexExists(String indexName) {
        try {
            return client.indices().exists(ExistsRequest.of(e -> e.index(indexName))).value();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void deleteIndex(String indexName) {
        try {
            if (indexExists(indexName)) {
                client.indices().delete(d -> d.index(indexName));
            }
        } catch (IOException e) {
            throw new RuntimeException("index deletion failed for " + indexName, e);
        }
    }
}
