package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.EvidenceRef;
import java.util.List;

public record VersionCaseView(int position, String question, String expectedBehavior,
                              String expectedAnswer, List<EvidenceRef> evidence) {
}
