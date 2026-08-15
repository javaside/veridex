package io.veridex.generation.api;

import io.veridex.conversation.api.MessageRecord;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import java.util.List;

/**
 * 生成编排端口：拒答判定 → 组装证据上下文 → 模型生成 → 引用校验。
 */
public interface GenerationService {

    GenerationResult generate(String question, List<EvidencePiece> evidence, List<MessageRecord> history);

    /**
     * 参数化生成：供评测运行按 ProfileConfig 驱动拒答阈值、prompt 模板与模型标识。
     */
    GenerationResult generate(String question, List<EvidencePiece> evidence,
                              List<MessageRecord> history, GenerationParameters parameters);
}
