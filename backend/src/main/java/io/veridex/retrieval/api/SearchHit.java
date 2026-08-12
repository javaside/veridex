package io.veridex.retrieval.api;

import java.util.UUID;

/**
 * 单路召回命中。channel 标识来源（BM25 或向量），score 为原始通道分数。
 */
public record SearchHit(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                        String title, String structurePath, String text, Channel channel, double score) {

    public enum Channel { BM25, VECTOR }
}
