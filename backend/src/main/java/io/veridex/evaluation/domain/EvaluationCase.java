package io.veridex.evaluation.domain;

import java.time.Instant;
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
@Table(name = "evaluation_case")
public class EvaluationCase {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_behavior", nullable = false, length = 20)
    private ExpectedBehavior expectedBehavior;

    @Column(name = "expected_answer", columnDefinition = "text")
    private String expectedAnswer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private String evidenceJson = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected EvaluationCase() {
    }

    public EvaluationCase(UUID datasetId, String question, ExpectedBehavior expectedBehavior,
                          String expectedAnswer, String evidenceJson) {
        this.datasetId = datasetId;
        this.question = question;
        this.expectedBehavior = expectedBehavior;
        this.expectedAnswer = expectedAnswer;
        this.evidenceJson = evidenceJson;
    }

    public void update(String question, ExpectedBehavior expectedBehavior,
                       String expectedAnswer, String evidenceJson) {
        this.question = question;
        this.expectedBehavior = expectedBehavior;
        this.expectedAnswer = expectedAnswer;
        this.evidenceJson = evidenceJson;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getDatasetId() { return datasetId; }
    public String getQuestion() { return question; }
    public ExpectedBehavior getExpectedBehavior() { return expectedBehavior; }
    public String getExpectedAnswer() { return expectedAnswer; }
    public String getEvidenceJson() { return evidenceJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
