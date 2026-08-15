package io.veridex.evaluation.api;

/**
 * 单项指标的对比（delta = candidate - baseline，越高越好指标正 = 改善）。
 */
public record MetricComparison(String name, double baseline, double candidate, double delta) {
}
