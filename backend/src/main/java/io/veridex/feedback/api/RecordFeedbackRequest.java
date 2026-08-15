package io.veridex.feedback.api;

import io.veridex.feedback.domain.FeedbackEvidence;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.feedback.domain.FeedbackReasonCode;
import java.util.List;
import java.util.UUID;

public record RecordFeedbackRequest(UUID queryRunId, FeedbackRating rating, FeedbackReasonCode reasonCode,
                                    String question, String answer, List<FeedbackEvidence> evidence) {
}
