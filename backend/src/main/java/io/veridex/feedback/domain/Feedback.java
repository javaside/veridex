package io.veridex.feedback.domain;

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
@Table(name = "feedback")
public class Feedback {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "query_run_id")
    private UUID queryRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FeedbackRating rating;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 40)
    private FeedbackReasonCode reasonCode;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Column(nullable = false, columnDefinition = "text")
    private String answer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, columnDefinition = "jsonb")
    private String evidenceJson = "[]";

    @Column(name = "converted_case_id")
    private UUID convertedCaseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Feedback() {
    }

    public Feedback(UUID userId, UUID queryRunId, FeedbackRating rating, FeedbackReasonCode reasonCode,
                    String question, String answer, String evidenceJson) {
        this.userId = userId;
        this.queryRunId = queryRunId;
        this.rating = rating;
        this.reasonCode = reasonCode;
        this.question = question;
        this.answer = answer;
        this.evidenceJson = evidenceJson;
    }

    public void markConverted(UUID caseId) {
        this.convertedCaseId = caseId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getQueryRunId() { return queryRunId; }
    public FeedbackRating getRating() { return rating; }
    public FeedbackReasonCode getReasonCode() { return reasonCode; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public String getEvidenceJson() { return evidenceJson; }
    public UUID getConvertedCaseId() { return convertedCaseId; }
    public Instant getCreatedAt() { return createdAt; }
}
