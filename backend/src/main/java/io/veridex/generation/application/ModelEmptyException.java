package io.veridex.generation.application;

/** 模型返回空响应（设计 §5.1）：归类 MODEL_ERROR。 */
public class ModelEmptyException extends GenerationModelException {

    public ModelEmptyException(String message) {
        super(message);
    }
}
