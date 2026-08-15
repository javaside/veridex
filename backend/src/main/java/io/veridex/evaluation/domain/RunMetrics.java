package io.veridex.evaluation.domain;

/**
 * 一次评测运行的聚合指标（全部 completed case 的均值/比率）。
 */
public record RunMetrics(double avgRecallAt1, double avgRecallAt3, double avgRecallAt5,
                         double avgMrr, double avgNdcgAt10, double citationHitRate,
                         double refusalMatchRate, double avgLatencyMs,
                         int caseCount, int completedCount) {
}
