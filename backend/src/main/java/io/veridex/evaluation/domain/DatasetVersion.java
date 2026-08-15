package io.veridex.evaluation.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "dataset_version")
public class DatasetVersion {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "case_count", nullable = false)
    private int caseCount;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DatasetVersion() {
    }

    public DatasetVersion(UUID datasetId, int versionNo, int caseCount, UUID createdBy) {
        this.datasetId = datasetId;
        this.versionNo = versionNo;
        this.caseCount = caseCount;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getDatasetId() { return datasetId; }
    public int getVersionNo() { return versionNo; }
    public int getCaseCount() { return caseCount; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
