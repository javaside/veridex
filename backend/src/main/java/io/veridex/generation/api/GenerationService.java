package io.veridex.generation.api;

import io.veridex.conversation.api.MessageRecord;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.shared.RefusalReason;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 生成编排端口：拒答判定 → 组装证据上下文 → 模型生成 → 引用校验。
 * 同步入口供评测执行；流式入口供在线问答（设计 D7）。
 */
public interface GenerationService {

    GenerationResult generate(String question, List<EvidencePiece> evidence, List<MessageRecord> history);

    /**
     * 参数化生成：供评测运行按 ProfileConfig 驱动拒答阈值与 prompt 模板。
     */
    GenerationResult generate(String question, List<EvidencePiece> evidence,
                              List<MessageRecord> history, GenerationParameters parameters);

    /**
     * 流式生成（设计 D6/D7）：返回生成生命周期事件，不暴露 Spring AI 类型。
     * cold、单次订阅；错误经 Flux error channel 传播；引用终检失败抛 InvalidCitationException。
     */
    Flux<GenerationEvent> stream(String question, List<EvidencePiece> evidence, List<MessageRecord> history);
}
