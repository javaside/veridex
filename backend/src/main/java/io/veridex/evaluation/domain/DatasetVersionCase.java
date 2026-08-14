package io.veridex.evaluation.domain;

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
@Table(name = "dataset_version_case")
public class DatasetVersionCase {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(nullable = false)
    private int position;

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

    protected DatasetVersionCase() {
    }

    public DatasetVersionCase(UUID versionId, int position, String question,
                              ExpectedBehavior expectedBehavior, String expectedAnswer,
                              String evidenceJson) {
        this.versionId = versionId;
        this.position = position;
        this.question = question;
        this.expectedBehavior = expectedBehavior;
        this.expectedAnswer = expectedAnswer;
        this.evidenceJson = evidenceJson;
    }

    public UUID getId() { return id; }
    public UUID getVersionId() { return versionId; }
    public int getPosition() { return position; }
    public String getQuestion() { return question; }
    public ExpectedBehavior getExpectedBehavior() { return expectedBehavior; }
    public String getExpectedAnswer() { return expectedAnswer; }
    public String getEvidenceJson() { return evidenceJson; }
}
