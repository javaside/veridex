package io.veridex.evaluation.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evaluation_run")
public class EvaluationRun {

    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "dataset_version_id", nullable = false)
    private UUID datasetVersionId;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "profile_version_no", nullable = false)
    private int profileVersionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "knowledge_scope", nullable = false, columnDefinition = "jsonb")
    private String knowledgeScopeJson = "[]";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.RUNNING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String metricsJson = "{}";

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    protected EvaluationRun() {
    }

    public EvaluationRun(UUID datasetId, UUID datasetVersionId, UUID profileId, int profileVersionNo,
                         List<UUID> knowledgeScope, UUID createdBy) {
        this.datasetId = datasetId;
        this.datasetVersionId = datasetVersionId;
        this.profileId = profileId;
        this.profileVersionNo = profileVersionNo;
        this.knowledgeScopeJson = knowledgeScope.stream().map(id -> "\"" + id + "\"").toList().toString();
        this.createdBy = createdBy;
    }

    public void complete(String metricsJson) {
        this.status = Status.COMPLETED;
        this.metricsJson = metricsJson;
        this.completedAt = Instant.now();
    }

    public void fail(String error) {
        this.status = Status.FAILED;
        this.error = error;
        this.completedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDatasetId() { return datasetId; }
    public UUID getDatasetVersionId() { return datasetVersionId; }
    public UUID getProfileId() { return profileId; }
    public int getProfileVersionNo() { return profileVersionNo; }
    public String getKnowledgeScopeJson() { return knowledgeScopeJson; }
    public Status getStatus() { return status; }
    public String getMetricsJson() { return metricsJson; }
    public String getError() { return error; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
