package io.veridex.evaluation.api;

/**
 * 回归门禁失败项：candidate 值低于允许的最低值 maxAllowed。
 */
public record GateCheck(String metric, double baseline, double candidate, double maxAllowed) {
}
