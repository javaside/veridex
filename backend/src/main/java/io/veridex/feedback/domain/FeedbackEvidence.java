package io.veridex.feedback.domain;

import java.util.List;
import java.util.UUID;

public record FeedbackEvidence(UUID documentVersionId, List<Integer> chunkIndexes) {
}
