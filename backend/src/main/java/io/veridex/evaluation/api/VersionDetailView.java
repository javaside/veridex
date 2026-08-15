package io.veridex.evaluation.api;

import java.util.List;
import java.util.UUID;

public record VersionDetailView(UUID id, int versionNo, int caseCount, String createdAt,
                                List<VersionCaseView> cases) {
}
