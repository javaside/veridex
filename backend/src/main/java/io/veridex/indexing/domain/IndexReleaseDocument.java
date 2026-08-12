package io.veridex.indexing.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * 快照文档清单：记录一次索引发布（知识库全量快照）包含哪些文档版本。
 */
@Entity
@Table(name = "index_release_document")
@IdClass(IndexReleaseDocumentId.class)
public class IndexReleaseDocument {

    @Id
    @Column(name = "release_id", nullable = false)
    private UUID releaseId;

    @Id
    @Column(name = "document_version_id", nullable = false)
    private UUID documentVersionId;

    protected IndexReleaseDocument() {
    }

    public IndexReleaseDocument(UUID releaseId, UUID documentVersionId) {
        this.releaseId = releaseId;
        this.documentVersionId = documentVersionId;
    }

    public UUID getReleaseId() { return releaseId; }
    public UUID getDocumentVersionId() { return documentVersionId; }
}
