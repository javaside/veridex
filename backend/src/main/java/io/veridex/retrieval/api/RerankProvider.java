package io.veridex.retrieval.api;

import java.util.List;

/**
 * Rerank 提供者接口。默认实现为直通（返回融合序），真实 rerank 服务按接口接入。
 */
public interface RerankProvider {

    List<SearchHit> rerank(List<SearchHit> hits, String question);
}
