package io.veridex.trace.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * 回答中的一个引用（[n]）及其校验状态。
 */
@Entity
@Table(name = "citation")
public class Citation {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "query_run_id", nullable = false)
    private UUID queryRunId;

    @Column(name = "citation_index", nullable = false)
    private int citationIndex;

    @Column(name = "document_version_id", nullable = false)
    private UUID documentVersionId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "source_location", length = 300)
    private String sourceLocation;

    @Lob
    @Column(name = "citation_text")
    private String citationText;

    @Column(name = "validation_status", nullable = false, length = 30)
    private String validationStatus;

    protected Citation() {
    }

    public Citation(UUID queryRunId, int citationIndex, UUID documentVersionId, int chunkIndex,
                    String sourceLocation, String citationText, String validationStatus) {
        this.queryRunId = queryRunId;
        this.citationIndex = citationIndex;
        this.documentVersionId = documentVersionId;
        this.chunkIndex = chunkIndex;
        this.sourceLocation = sourceLocation;
        this.citationText = citationText;
        this.validationStatus = validationStatus;
    }

    public UUID getId() { return id; }
    public UUID getQueryRunId() { return queryRunId; }
    public int getCitationIndex() { return citationIndex; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getValidationStatus() { return validationStatus; }
}
