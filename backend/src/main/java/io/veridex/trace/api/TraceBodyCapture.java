package io.veridex.trace.api;

import java.util.List;
import java.util.UUID;

public interface TraceBodyCapture {
    enum TerminalOutcome { COMPLETED, REFUSED, FAILED, CANCELLED }
    record PromptMessage(String role, String content) { }
    record EvidenceSnapshot(int citationIndex, UUID documentVersionId, int chunkIndex,
                            String title, String structurePath, String text) { }
    record CitationSnapshot(int citationIndex, UUID documentVersionId, int chunkIndex,
                            String sourceLocation, String citationText, String validationStatus) { }
    record TraceBodyMaterial(String question, List<PromptMessage> promptMessages, String answer,
                             List<EvidenceSnapshot> evidence, List<CitationSnapshot> citations) { }
    void capture(UUID runId, TerminalOutcome outcome, String errorCode, TraceBodyMaterial material);
}
