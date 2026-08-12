package io.veridex.retrieval.api;

import java.util.UUID;

/**
 * 融合排序后的单个命中明细，供 trace 模块记录 RetrievalHit（渠道、分数、排名、是否进入上下文）。
 */
public record RankedHitView(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                            String channel, Double bm25Score, Double vectorScore, Double fusionScore,
                            int rank, boolean enteredContext, String filterReason) {
}
