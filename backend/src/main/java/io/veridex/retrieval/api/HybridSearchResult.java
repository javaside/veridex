package io.veridex.retrieval.api;

import java.util.List;

/**
 * 一次混合检索的结果：进入上下文的证据 + 全部融合命中明细（供 trace 记录 RetrievalHit）
 * + 本次检索的降级记录（单路失败时另一路继续，降级说明写入 generation_run.degradation）。
 */
public record HybridSearchResult(List<EvidencePiece> evidence, List<RankedHitView> hits,
                                 List<String> degradations) {
}
