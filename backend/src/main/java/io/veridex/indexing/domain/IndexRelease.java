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

    @Column(name = "document_version_id", nullable = false)
    private UUID documentVersionId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "index_name", nullable = false, length = 300)
    private String indexName;

    @Column(name = "alias_name", nullable = false, length = 300)
    private String aliasName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private IndexReleaseStatus status = IndexReleaseStatus.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "published_at")
    private Instant publishedAt;

    protected IndexRelease() {
    }

    public IndexRelease(UUID knowledgeBaseId, UUID documentVersionId, int versionNo,
                        String indexName, String aliasName) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentVersionId = documentVersionId;
        this.versionNo = versionNo;
        this.indexName = indexName;
        this.aliasName = aliasName;
    }

    public UUID getId() { return id; }
    public UUID getKnowledgeBaseId() { return knowledgeBaseId; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public int getVersionNo() { return versionNo; }
    public String getIndexName() { return indexName; }
    public String getAliasName() { return aliasName; }
    public IndexReleaseStatus getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }

    public void publish() {
        this.status = IndexReleaseStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    public void rollback() { this.status = IndexReleaseStatus.ROLLED_BACK; }
    public void offline() { this.status = IndexReleaseStatus.OFFLINE; }
}
