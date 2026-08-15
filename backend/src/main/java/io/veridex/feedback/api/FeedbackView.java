package io.veridex.feedback.api;

import io.veridex.feedback.domain.FeedbackEvidence;
import java.util.List;
import java.util.UUID;

public record FeedbackView(UUID id, UUID queryRunId, String rating, String reasonCode,
                           String question, String answer, List<FeedbackEvidence> evidence,
                           UUID convertedCaseId, String createdAt) {
}
