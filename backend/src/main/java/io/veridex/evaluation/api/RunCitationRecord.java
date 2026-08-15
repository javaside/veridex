package io.veridex.evaluation.api;

import java.util.UUID;

/**
 * 评测用例中生成的引用快照（[n] 及其 chunk 与校验状态）。
 */
public record RunCitationRecord(int citationIndex, UUID documentVersionId, int chunkIndex,
                                String validationStatus) {
}
