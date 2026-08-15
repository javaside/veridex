package io.veridex.evaluation.api;

import java.util.UUID;

public record CompareRunsRequest(UUID baselineRunId, UUID candidateRunId) {
}
