package io.veridex.conversation.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "message")
public class Message {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(nullable = false, length = 20)
    private String role;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "query_run_id")
    private UUID queryRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Message() {
    }

    public Message(UUID conversationId, String role, String content, UUID queryRunId) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.queryRunId = queryRunId;
    }

    public UUID getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public UUID getQueryRunId() { return queryRunId; }
    public Instant getCreatedAt() { return createdAt; }
}
