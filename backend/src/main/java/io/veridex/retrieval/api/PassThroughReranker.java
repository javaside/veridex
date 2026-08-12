package io.veridex.retrieval.api;

import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 默认 RerankProvider：直通（返回融合序）。真实 rerank 在 Phase 4 接入，超时/未接入时退回此实现。
 */
@Component
public class PassThroughReranker implements RerankProvider {

    @Override
    public List<SearchHit> rerank(List<SearchHit> hits, String question) {
        return hits;
    }
}
