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
@Table(name = "document_version")
public class DocumentVersion {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DocumentVersionStatus status = DocumentVersionStatus.UPLOADED;

    @Column(name = "parsed_object_key", length = 500)
    private String parsedObjectKey;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount = 0;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    protected DocumentVersion() {
    }

    public DocumentVersion(UUID documentId, int versionNo, String objectKey, String fileSha256) {
        this.documentId = documentId;
        this.versionNo = versionNo;
        this.objectKey = objectKey;
        this.fileSha256 = fileSha256;
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public int getVersionNo() { return versionNo; }
    public String getObjectKey() { return objectKey; }
    public String getFileSha256() { return fileSha256; }
    public DocumentVersionStatus getStatus() { return status; }
    public String getParsedObjectKey() { return parsedObjectKey; }
    public int getChunkCount() { return chunkCount; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }

    public void markProcessing() {
        if (status != DocumentVersionStatus.UPLOADED) {
            throw new IllegalStateException("cannot mark " + status + " as PROCESSING");
        }
        this.status = DocumentVersionStatus.PROCESSING;
    }

    public void markReady(int chunkCount) {
        if (status != DocumentVersionStatus.PROCESSING && status != DocumentVersionStatus.READY) {
            throw new IllegalStateException("cannot mark " + status + " as READY");
        }
        this.status = DocumentVersionStatus.READY;
        this.chunkCount = chunkCount;
        this.processedAt = Instant.now();
        this.errorMessage = null;
    }

    public void markFailed(String reason) {
        this.status = DocumentVersionStatus.FAILED;
        this.errorMessage = reason;
    }

    public void setParsedObjectKey(String key) {
        this.parsedObjectKey = key;
    }
}
