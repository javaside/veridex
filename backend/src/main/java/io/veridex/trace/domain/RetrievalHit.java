package io.veridex.trace.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次召回的单个命中记录（渠道、各阶段分数、排名、是否进入上下文、过滤原因）。
 */
@Entity
@Table(name = "retrieval_hit")
public class RetrievalHit {

    public enum Channel { BM25, VECTOR }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "query_run_id", nullable = false)
    private UUID queryRunId;

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "document_version_id", nullable = false)
    private UUID documentVersionId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel;

    @Column(name = "bm25_score")
    private Double bm25Score;

    @Column(name = "vector_score")
    private Double vectorScore;

    @Column(name = "fusion_score")
    private Double fusionScore;

    @Column(nullable = false)
    private int rank;

    @Column(name = "entered_context", nullable = false)
    private boolean enteredContext;

    @Column(name = "filter_reason", length = 100)
    private String filterReason;

    protected RetrievalHit() {
    }

    public RetrievalHit(UUID queryRunId, UUID knowledgeBaseId, UUID documentVersionId, int chunkIndex,
                        Channel channel, Double bm25Score, Double vectorScore, Double fusionScore,
                        int rank, boolean enteredContext, String filterReason) {
        this.queryRunId = queryRunId;
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentVersionId = documentVersionId;
        this.chunkIndex = chunkIndex;
        this.channel = channel;
        this.bm25Score = bm25Score;
        this.vectorScore = vectorScore;
        this.fusionScore = fusionScore;
        this.rank = rank;
        this.enteredContext = enteredContext;
        this.filterReason = filterReason;
    }

    public UUID getId() { return id; }
    public UUID getQueryRunId() { return queryRunId; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public int getChunkIndex() { return chunkIndex; }
    public Channel getChannel() { return channel; }
    public int getRank() { return rank; }
    public boolean isEnteredContext() { return enteredContext; }
}
