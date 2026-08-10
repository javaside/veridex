package io.veridex.knowledge.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_base_grant")
@IdClass(KnowledgeBaseGrantId.class)
public class KnowledgeBaseGrant {

    @Id
    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private GrantLevel level;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    protected KnowledgeBaseGrant() {
    }

    public KnowledgeBaseGrant(UUID knowledgeBaseId, UUID userId, GrantLevel level) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.userId = userId;
        this.level = level;
    }

    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public UUID getUserId() { return userId; }
    public GrantLevel getLevel() { return level; }
    public Instant getGrantedAt() { return grantedAt; }
}
