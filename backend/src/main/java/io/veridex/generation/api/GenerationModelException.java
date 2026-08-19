package io.veridex.generation.api;

/**
 * 模型失败族（设计 §5.1/§5.2/§4.5）：无 deterministic 回退，错误经 Flux error channel 传播。
 * 消息不包含敏感内容（prompt/证据/completion）。
 */
public class GenerationModelException extends RuntimeException {

    public GenerationModelException(String message) {
        super(message);
    }
}
