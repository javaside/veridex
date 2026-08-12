package io.veridex.generation.application;

import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 拒答判定：证据为空 → NO_RELEVANT_EVIDENCE；证据过少 → INSUFFICIENT_EVIDENCE；
 * 足够 → 允许生成（返回 null）。
 */
@Component
public class RefusalPolicy {

    private static final int MIN_EVIDENCE_CHARS = 50;

    public RefusalReason evaluate(List<EvidencePiece> evidence) {
        if (evidence.isEmpty()) {
            return RefusalReason.NO_RELEVANT_EVIDENCE;
        }
        int chars = evidence.stream().mapToInt(e -> e.text().length()).sum();
        if (chars < MIN_EVIDENCE_CHARS) {
            return RefusalReason.INSUFFICIENT_EVIDENCE;
        }
        return null;
    }
}
