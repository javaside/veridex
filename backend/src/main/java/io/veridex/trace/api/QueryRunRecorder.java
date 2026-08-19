package io.veridex.trace.api;

import io.veridex.shared.RefusalReason;
import java.util.List;
import java.util.UUID;

/**
 * QueryRun 与证据链持久化端口（trace 模块对外 API）。
 */
public interface QueryRunRecorder {

    UUID start(UUID userId, UUID conversationId, List<UUID> knowledgeScope, String normalizedQuestion);

    void markRetrieving(UUID runId, List<RetrievalHitRecord> hits);

    /** 生成开始：仅把 QueryRun 置为 GENERATING（设计 §4.4 步骤 5）。 */
    void markGenerating(UUID runId);

    /** 生成结束：记录/回填 GenerationRun 指标行（幂等 upsert）。 */
    void recordGeneration(UUID runId, GenerationRecord gen);

    void addCitations(UUID runId, List<CitationRecord> citations);

    void complete(UUID runId);

    void refuse(UUID runId, RefusalReason reason);

    void fail(UUID runId, String error);

    void cancel(UUID runId);

    record RetrievalHitRecord(UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex, String channel,
                              Double bm25Score, Double vectorScore, Double fusionScore, int rank,
                              boolean enteredContext, String filterReason) {
    }

    record GenerationRecord(String provider, String model, int inputTokens, int outputTokens,
                            long durationMs, long firstTokenLatencyMs, String degradation, String contextHash) {
    }

    record CitationRecord(int citationIndex, UUID documentVersionId, int chunkIndex,
                          String sourceLocation, String citationText, String validationStatus) {
    }
}
