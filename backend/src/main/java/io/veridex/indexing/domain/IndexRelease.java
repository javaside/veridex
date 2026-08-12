package io.veridex.indexing.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "index_release")
public class IndexRelease {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "knowledge_base_id", nullable = false)
    private UUID knowledgeBaseId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "index_name", nullable = false, length = 300)
    private String indexName;

    @Column(name = "alias_name", nullable = false, length = 300)
    private String aliasName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IndexReleaseStatus status = IndexReleaseStatus.DRAFT;

    @Column(name = "document_count", nullable = false)
    private int documentCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected IndexRelease() {
    }

    public IndexRelease(UUID knowledgeBaseId, int versionNo, String indexName, String aliasName) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.versionNo = versionNo;
        this.indexName = indexName;
        this.aliasName = aliasName;
    }

    public UUID getId() { return id; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public int getVersionNo() { return versionNo; }
    public String getIndexName() { return indexName; }
    public String getAliasName() { return aliasName; }
    public IndexReleaseStatus getStatus() { return status; }
    public int getDocumentCount() { return documentCount; }
    public int getChunkCount() { return chunkCount; }
    public boolean isActive() { return isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }

    public void publish() {
        this.status = IndexReleaseStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public void rollback() { this.status = IndexReleaseStatus.ROLLED_BACK; }
    public void offline() { this.status = IndexReleaseStatus.OFFLINE; }

    public void markActive() { this.isActive = true; }
    public void markInactive() { this.isActive = false; }
    public void setStats(int documentCount, int chunkCount) {
        this.documentCount = documentCount;
        this.chunkCount = chunkCount;
    }
}
