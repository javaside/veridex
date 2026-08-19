package io.veridex.generation.application;

/**
 * 引用终检失败（设计 §4.5）：完整文本已聚合但引用无法映射到本次授权证据，
 * 不发 Completed；稳定错误码 INVALID_CITATION。
 */
public class InvalidCitationException extends GenerationModelException {

    public InvalidCitationException(String message) {
        super(message);
    }
}
