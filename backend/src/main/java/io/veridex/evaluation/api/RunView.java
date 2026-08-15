package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.RunMetrics;
import java.util.UUID;

public record RunView(UUID id, UUID datasetId, UUID datasetVersionId, UUID profileId, int profileVersionNo,
                      String status, RunMetrics metrics, String createdAt, String completedAt) {
}
