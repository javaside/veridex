package io.veridex.generation.api;

/** 模型生成超时（设计 §5.2）：稳定子码 MODEL_TIMEOUT。 */
public class ModelTimeoutException extends GenerationModelException {

    public ModelTimeoutException(String message) {
        super(message);
    }
}
