package io.veridex.trace.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 一次问答的完整执行记录（在线问答链路 Trace）。只存问题与元数据，不存 prompt/检索正文。
 */
@Entity
@Table(name = "query_run")
public class QueryRun {

    public enum Status {
        RECEIVED, RETRIEVING, RERANKING, GENERATING, VALIDATING,
        COMPLETED, REFUSED, FAILED, CANCELLED
    }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "normalized_question", columnDefinition = "text")
    private String normalizedQuestion;

    @Column(name = "knowledge_scope", nullable = false, columnDefinition = "jsonb")
    private String knowledgeScopeJson = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.RECEIVED;

    @Column(name = "refusal_reason", length = 60)
    private String refusalReason;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected QueryRun() {
    }

    public QueryRun(UUID userId, UUID conversationId, String question, String normalizedQuestion,
                    List<UUID> knowledgeScope) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.question = question;
        this.normalizedQuestion = normalizedQuestion;
        this.knowledgeScopeJson = knowledgeScope.stream().map(UUID::toString).toList().toString();
    }

    public void mark(Status next) {
        this.status = next;
    }

    public void complete() {
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void refuse(String reason) {
        this.status = Status.REFUSED;
        this.refusalReason = reason;
        this.completedAt = Instant.now();
    }

    public void fail(String error) {
        this.status = Status.FAILED;
        this.error = error;
        this.completedAt = Instant.now();
    }

    public void cancel() {
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getConversationId() { return conversationId; }
    public String getQuestion() { return question; }
    public Status getStatus() { return status; }
    public String getRefusalReason() { return refusalReason; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
