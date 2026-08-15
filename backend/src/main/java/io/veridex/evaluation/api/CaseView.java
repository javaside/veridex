package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.EvidenceRef;
import java.util.List;
import java.util.UUID;

public record CaseView(UUID id, String question, String expectedBehavior,
                       String expectedAnswer, List<EvidenceRef> evidence) {
}
