package io.veridex.knowledge.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 200)
    private String slug;

    @Column
    private String description;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private KnowledgeBaseStatus status = KnowledgeBaseStatus.ACTIVE;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected KnowledgeBase() {
    }

    public KnowledgeBase(String name, String slug, String description, UUID ownerId) {
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.ownerId = ownerId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public UUID getOwnerId() { return ownerId; }
    public KnowledgeBaseStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
