package io.veridex.retrieval.application;

import io.veridex.retrieval.api.RerankProvider;
import io.veridex.retrieval.api.SearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 语义重排：直接复用向量检索已返回的余弦相似度（{@link SearchHit#vectorScore()}）排序，
 * 不再对候选分块重新调用 embedding——本地 embedding 模型对几十篇长文本的批量编码耗时 10s+，
 * 是端到端延迟的主因。向量通道的 score 与索引同属一个向量空间，跨库可比。
 *
 * <p>仅有 BM25 命中（无向量分数）的 chunk 退化为按融合分（fusionScore）排在后面，
 * 语义上本就相关性较弱，不影响「最相关优先」的主目标。
 */
@Component
@Primary
public class SemanticReranker implements RerankProvider {

    @Override
    public List<SearchHit> rerank(List<SearchHit> hits, String question) {
        if (hits == null || hits.size() <= 1) {
            return hits == null ? List.of() : hits;
        }
        List<SearchHit> out = new ArrayList<>(hits);
        out.sort((a, b) -> {
            boolean aHasVector = a.vectorScore() != null;
            boolean bHasVector = b.vectorScore() != null;
            // 有向量相似度的排前面；都没有时按融合分降序兜底。
            if (aHasVector != bHasVector) {
                return aHasVector ? -1 : 1;
            }
            if (aHasVector) {
                return Double.compare(b.vectorScore(), a.vectorScore());
            }
            return Double.compare(b.score(), a.score());
        });
        return out;
    }
}
