package io.veridex.retrieval.infrastructure;

import io.veridex.retrieval.api.SearchHit;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 基于 OpenSearch alias 的双路召回读取器：BM25 全文 + kNN 向量，各自独立返回 Top K。
 * 查询只打 alias（当前 active 快照），不关心具体 index。
 */
@Component
public class OpenSearchRetrievalReader {

    private final OpenSearchClient client;
    private final EmbeddingModel embeddings;

    public OpenSearchRetrievalReader(OpenSearchClient client, EmbeddingModel embeddings) {
        this.client = client;
        this.embeddings = embeddings;
    }

    public List<SearchHit> bm25(String aliasName, UUID knowledgeBaseId, String question, int topK) {
        var request = SearchRequest.of(s -> s.index(aliasName).size(topK)
                .query(q -> q.match(m -> m.field("text")
                        .query(org.opensearch.client.opensearch._types.FieldValue.of(question)))));
        return mapHits(request, knowledgeBaseId, SearchHit.Channel.BM25);
    }

    public List<SearchHit> vector(String aliasName, UUID knowledgeBaseId, String question, int topK) {
        float[] vector = embeddings.embed(question);
        List<Float> vectorList = new ArrayList<>(vector.length);
        for (float v : vector) {
            vectorList.add(v);
        }
        var request = SearchRequest.of(s -> s.index(aliasName).size(topK)
                .query(q -> q.knn(k -> k.field("embedding").vector(vectorList).k(topK))));
        return mapHits(request, knowledgeBaseId, SearchHit.Channel.VECTOR);
    }

    @SuppressWarnings("unchecked")
    private List<SearchHit> mapHits(SearchRequest request, UUID knowledgeBaseId, SearchHit.Channel channel) {
        try {
            var response = client.search(request, Map.class);
            List<SearchHit> out = new ArrayList<>();
            for (var hit : response.hits().hits()) {
                Map<String, Object> src = (Map<String, Object>) hit.source();
                if (src == null) {
                    continue;
                }
                out.add(new SearchHit(
                        knowledgeBaseId,
                        UUID.fromString(String.valueOf(src.get("document_version_id"))),
                        ((Number) src.get("chunk_index")).intValue(),
                        String.valueOf(src.get("title")),
                        String.valueOf(src.get("structure_path")),
                        String.valueOf(src.get("text")),
                        channel,
                        hit.score() != null ? hit.score() : 0d));
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("retrieval search failed on " + request.index(), e);
        }
    }
}
