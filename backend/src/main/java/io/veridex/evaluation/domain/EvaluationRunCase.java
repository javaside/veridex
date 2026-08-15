package io.veridex.evaluation.domain;

import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evaluation_run_case")
public class EvaluationRunCase {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "case_position", nullable = false)
    private int casePosition;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "expected_behavior", nullable = false, length = 20)
    private String expectedBehavior;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ground_truth_evidence", nullable = false, columnDefinition = "jsonb")
    private String groundTruthEvidenceJson = "[]";

    @Column(name = "actual_behavior", nullable = false, length = 20)
    private String actualBehavior;

    @Column(columnDefinition = "text")
    private String answer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citations", nullable = false, columnDefinition = "jsonb")
    private String citationsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_chunks", nullable = false, columnDefinition = "jsonb")
    private String retrievedChunksJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", nullable = false, columnDefinition = "jsonb")
    private String metricsJson = "{}";

    protected EvaluationRunCase() {
    }

    public EvaluationRunCase(UUID runId, int casePosition, String question, String expectedBehavior,
                             String groundTruthEvidenceJson, String actualBehavior, String answer,
                             String citationsJson, String retrievedChunksJson, String metricsJson) {
        this.runId = runId;
        this.casePosition = casePosition;
        this.question = question;
        this.expectedBehavior = expectedBehavior;
        this.groundTruthEvidenceJson = groundTruthEvidenceJson;
        this.actualBehavior = actualBehavior;
        this.answer = answer;
        this.citationsJson = citationsJson;
        this.retrievedChunksJson = retrievedChunksJson;
        this.metricsJson = metricsJson;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public int getCasePosition() { return casePosition; }
    public String getQuestion() { return question; }
    public String getExpectedBehavior() { return expectedBehavior; }
    public String getGroundTruthEvidenceJson() { return groundTruthEvidenceJson; }
    public String getActualBehavior() { return actualBehavior; }
    public String getAnswer() { return answer; }
    public String getCitationsJson() { return citationsJson; }
    public String getRetrievedChunksJson() { return retrievedChunksJson; }
    public String getMetricsJson() { return metricsJson; }
}
