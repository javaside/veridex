package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.RunMetrics;
import java.util.List;
import java.util.UUID;

public record RunDetailView(UUID id, UUID datasetId, UUID datasetVersionId, UUID profileId, int profileVersionNo,
                            String status, RunMetrics metrics, String error, String createdAt,
                            String completedAt, List<RunCaseView> cases) {
}
