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
}
