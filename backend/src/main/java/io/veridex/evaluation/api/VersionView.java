package io.veridex.evaluation.api;

import java.util.UUID;

public record VersionView(UUID id, int versionNo, int caseCount, String createdAt) {
}
