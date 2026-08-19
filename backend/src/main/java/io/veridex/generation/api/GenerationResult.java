package io.veridex.generation.api;

import io.veridex.shared.RefusalReason;
import java.util.List;

/**
 * 一次生成的结果。拒答时 answer 为 null 且 refusalReason 非空。
 * provider 为实际装配的 provider（deterministic/ollama）；model 为实际调用模型名
 * （设计 §6：不再使用评测 Profile 中未接路由的任意字符串作为实际调用模型）。
 */
public record GenerationResult(String answer, List<CitationView> citations,
                               RefusalReason refusalReason, String provider, String model,
                               int inputTokens, int outputTokens, long durationMs,
                               long firstTokenLatencyMs, boolean usageEstimated,
                               String contextHash, List<PromptMessageView> promptMessages) {

    /** 兼容便捷构造：默认 deterministic provider、无首 token 时延、usage 估算。 */
    public GenerationResult(String answer, List<CitationView> citations, RefusalReason refusalReason,
                            String model, int inputTokens, int outputTokens, long durationMs, String contextHash) {
        this(answer, citations, refusalReason, "deterministic", model, inputTokens, outputTokens,
                durationMs, 0, true, contextHash, List.of());
    }
}
