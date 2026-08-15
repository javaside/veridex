package io.veridex.evaluation.api;

import java.util.List;
import java.util.UUID;

public record StartRunRequest(UUID datasetId, int datasetVersionNo, UUID profileId, int profileVersionNo,
                              List<UUID> knowledgeBaseIds) {
}
