package io.veridex.evaluation.application;

import io.veridex.evaluation.api.ComparisonResult;
import io.veridex.evaluation.api.GateCheck;
import io.veridex.evaluation.api.GateVerdict;
import io.veridex.evaluation.api.MetricComparison;
import io.veridex.evaluation.domain.EvaluationRun;
import io.veridex.evaluation.domain.EvaluationRunRepository;
import io.veridex.evaluation.domain.RunMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * 评测运行对比与回归门禁（纯计算，不落库）。
 *
 * 门禁规则：相对退化阈值 10%；四类关键指标（MRR/NDCG@10/citationHit/refusalMatch）参与判定；
 * 任一 run 不完整（status 非 COMPLETED 或 completedCount != caseCount）→ INCOMPLETE。
 */
@Service
public class EvaluationComparisonService {

    private static final double MAX_REGRESSION = 0.10;

    private final EvaluationRunRepository runs;
    private final JsonMapper jsonMapper;

    public EvaluationComparisonService(EvaluationRunRepository runs, JsonMapper jsonMapper) {
        this.runs = runs;
        this.jsonMapper = jsonMapper;
    }

    public ComparisonResult compare(UUID baselineRunId, UUID candidateRunId) {
        EvaluationRun baselineRun = requireRun(baselineRunId);
        EvaluationRun candidateRun = requireRun(candidateRunId);

        if (baselineRun.getStatus() != EvaluationRun.Status.COMPLETED
                || candidateRun.getStatus() != EvaluationRun.Status.COMPLETED) {
            return new ComparisonResult(baselineRunId, candidateRunId, null, null,
                    List.of(), GateVerdict.INCOMPLETE, List.of());
        }

        RunMetrics baseline = deserialize(baselineRun.getMetricsJson());
        RunMetrics candidate = deserialize(candidateRun.getMetricsJson());
        if (baseline == null || candidate == null
                || baseline.completedCount() != baseline.caseCount()
                || candidate.completedCount() != candidate.caseCount()) {
            return new ComparisonResult(baselineRunId, candidateRunId, baseline, candidate,
                    List.of(), GateVerdict.INCOMPLETE, List.of());
        }

        List<MetricComparison> metrics = List.of(
                metric("Recall@1", baseline.avgRecallAt1(), candidate.avgRecallAt1()),
                metric("Recall@3", baseline.avgRecallAt3(), candidate.avgRecallAt3()),
                metric("Recall@5", baseline.avgRecallAt5(), candidate.avgRecallAt5()),
                metric("MRR", baseline.avgMrr(), candidate.avgMrr()),
                metric("NDCG@10", baseline.avgNdcgAt10(), candidate.avgNdcgAt10()),
                metric("Citation hit", baseline.citationHitRate(), candidate.citationHitRate()),
                metric("Refusal match", baseline.refusalMatchRate(), candidate.refusalMatchRate()),
                metric("Avg latency (ms)", baseline.avgLatencyMs(), candidate.avgLatencyMs()));

        List<GateCheck> failed = new ArrayList<>();
        checkRegression(failed, "MRR", baseline.avgMrr(), candidate.avgMrr());
        checkRegression(failed, "NDCG@10", baseline.avgNdcgAt10(), candidate.avgNdcgAt10());
        checkRegression(failed, "Citation hit", baseline.citationHitRate(), candidate.citationHitRate());
        checkRegression(failed, "Refusal match", baseline.refusalMatchRate(), candidate.refusalMatchRate());

        GateVerdict verdict = failed.isEmpty() ? GateVerdict.PASS : GateVerdict.FAIL;
        return new ComparisonResult(baselineRunId, candidateRunId, baseline, candidate, metrics, verdict, failed);
    }

    private EvaluationRun requireRun(UUID runId) {
        return runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown evaluation run " + runId));
    }

    private RunMetrics deserialize(String json) {
        try {
            return json == null || json.isBlank() ? null : jsonMapper.readValue(json, RunMetrics.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static MetricComparison metric(String name, double baseline, double candidate) {
        return new MetricComparison(name, baseline, candidate, candidate - baseline);
    }

    private static void checkRegression(List<GateCheck> failed, String metric,
                                        double baseline, double candidate) {
        double maxAllowed = baseline * (1.0 - MAX_REGRESSION);
        if (candidate < maxAllowed) {
            failed.add(new GateCheck(metric, baseline, candidate, maxAllowed));
        }
    }
}
