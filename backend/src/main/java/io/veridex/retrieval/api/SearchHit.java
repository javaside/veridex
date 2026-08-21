package io.veridex.retrieval.api;

import java.util.UUID;

/**
 * 单路召回命中。channel 标识来源（BM25 或向量），score 为原始通道分数。
 * vectorScore 为向量通道的余弦相似度（跨库可比），供语义重排使用；BM25 单独命中时为 null。
 */
public record SearchHit(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                        String title, String structurePath, String text, Channel channel,
                        double score, Double vectorScore) {

    /** 无向量分数的兼容构造器（vectorScore 视为 null）。 */
    public SearchHit(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                     String title, String structurePath, String text, Channel channel, double score) {
        this(knowledgeBaseId, documentVersionId, chunkIndex, title, structurePath, text, channel, score, null);
    }

    public enum Channel { BM25, VECTOR }
}
