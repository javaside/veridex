package io.veridex.evaluation.api;

import java.util.UUID;

public record DatasetView(UUID id, String name, String description, int caseCount, Integer latestVersionNo) {
}
