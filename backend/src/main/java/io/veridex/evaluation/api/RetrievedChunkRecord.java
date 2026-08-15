package io.veridex.evaluation.api;

import java.util.UUID;

/**
 * 评测用例检索命中的 chunk 快照（融合排名与分数）。
 */
public record RetrievedChunkRecord(UUID documentVersionId, int chunkIndex, int rank, Double fusionScore) {
}
