package io.veridex.generation.api;

/**
 * 生成可调参数（供评测运行按 ProfileConfig 驱动拒答阈值、prompt 模板与模型标识）。
 * 模型标识仅记录，不做运行时路由（当前只有单一模型实现）。
 */
public record GenerationParameters(int minEvidenceChars, String systemTemplate, String model) {
}
