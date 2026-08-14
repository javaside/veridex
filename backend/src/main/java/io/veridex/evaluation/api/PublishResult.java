package io.veridex.evaluation.api;

import java.util.UUID;

public record PublishResult(UUID versionId, int versionNo, int caseCount) {
}
