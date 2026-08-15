package io.veridex.evaluation.api;

import io.veridex.evaluation.domain.RunMetrics;
import java.util.List;
import java.util.UUID;

/**
 * 两个评测运行的对比报告（含逐指标对比与回归门禁判定）。
 */
public record ComparisonResult(UUID baselineRunId, UUID candidateRunId,
                               RunMetrics baseline, RunMetrics candidate,
                               List<MetricComparison> metrics, GateVerdict verdict,
                               List<GateCheck> failedChecks) {
}
