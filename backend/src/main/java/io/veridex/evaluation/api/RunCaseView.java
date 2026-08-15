package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.CaseMetrics;
import io.veridex.evaluation.domain.EvidenceRef;
import java.util.List;

public record RunCaseView(int position, String question, String expectedBehavior, String actualBehavior,
                          String answer, List<EvidenceRef> groundTruthEvidence,
                          List<RunCitationRecord> citations, List<RetrievedChunkRecord> retrievedChunks,
                          CaseMetrics metrics) {
}
