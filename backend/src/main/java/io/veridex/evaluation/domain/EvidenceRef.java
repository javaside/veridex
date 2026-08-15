package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;

public record EvidenceRef(UUID documentVersionId, List<Integer> chunkIndexes) {
}
