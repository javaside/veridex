package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.evaluation.domain.ExpectedBehavior;
import java.util.List;

public record CreateCaseRequest(String question, ExpectedBehavior expectedBehavior,
                                String expectedAnswer, List<EvidenceRef> evidence) {
}
