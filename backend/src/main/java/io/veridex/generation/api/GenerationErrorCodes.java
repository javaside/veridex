package io.veridex.generation.api;

import io.veridex.shared.observability.TelemetryErrorCode;

/**
 * 生成模块的错误分类：把模型失败族映射为稳定错误码
 * （MODEL_TIMEOUT/INVALID_CITATION/MODEL_ERROR），其余委托 {@link TelemetryErrorCode}。
 * 放在 generation 模块内，避免 shared 反向依赖 generation（模块环，ArchitectureTest 强制）。
 */
public final class GenerationErrorCodes {

    private GenerationErrorCodes() {
    }

    public static TelemetryErrorCode classify(Throwable error) {
        if (error instanceof ModelTimeoutException) {
            return TelemetryErrorCode.MODEL_TIMEOUT;
        }
        if (error instanceof InvalidCitationException) {
            return TelemetryErrorCode.INVALID_CITATION;
        }
        if (error instanceof GenerationModelException) {
            return TelemetryErrorCode.MODEL_ERROR;
        }
        return TelemetryErrorCode.classify(error);
    }
}
