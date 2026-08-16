package io.veridex.generation.api;

import io.veridex.shared.RefusalReason;
import java.util.List;

/**
 * 一次生成的结果。拒答时 answer 为 null 且 refusalReason 非空。
 */
public record GenerationResult(String answer, List<CitationView> citations,
                               RefusalReason refusalReason, String model,
                               int inputTokens, int outputTokens, long durationMs, String contextHash,
                               List<PromptMessageView> promptMessages) {
    public GenerationResult(String answer, List<CitationView> citations, RefusalReason refusalReason,
                            String model, int inputTokens, int outputTokens, long durationMs, String contextHash) {
        this(answer, citations, refusalReason, model, inputTokens, outputTokens, durationMs, contextHash, List.of());
    }
}
