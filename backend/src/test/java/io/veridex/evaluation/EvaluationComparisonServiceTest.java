package io.veridex.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.veridex.evaluation.api.ComparisonResult;
import io.veridex.evaluation.api.GateVerdict;
import io.veridex.evaluation.application.EvaluationComparisonService;
import io.veridex.evaluation.domain.EvaluationRun;
import io.veridex.evaluation.domain.EvaluationRunRepository;
import io.veridex.evaluation.domain.RunMetrics;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EvaluationComparisonServiceTest {

    private EvaluationRunRepository runs;
    private EvaluationComparisonService service;
    private final JsonMapper jsonMapper = new JsonMapper();

    private static final UUID BASELINE_ID = UUID.randomUUID();
    private static final UUID CANDIDATE_ID = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();
    private static final UUID DATASET_VERSION = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runs = mock(EvaluationRunRepository.class);
        service = new EvaluationComparisonService(runs, jsonMapper);
    }

    private EvaluationRun run(UUID id, RunMetrics metrics) {
        EvaluationRun run = new EvaluationRun(DATASET, DATASET_VERSION, PROFILE, 1, List.of(), ACTOR);
        run.complete(toJson(metrics));
        return run;
    }

    private String toJson(RunMetrics metrics) {
        try {
            return jsonMapper.writeValueAsString(metrics);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static RunMetrics metrics(double mrr, double ndcg, double citationHit, double refusalMatch,
                                      int caseCount, int completedCount) {
        return new RunMetrics(0.5, 0.5, 0.5, mrr, ndcg, citationHit, refusalMatch, 100,
                caseCount, completedCount);
    }

    @Test
    void passesWhenNoMetricRegresses() {
        when(runs.findById(BASELINE_ID)).thenReturn(Optional.of(run(BASELINE_ID, metrics(0.5, 0.5, 0.5, 0.5, 3, 3))));
        when(runs.findById(CANDIDATE_ID)).thenReturn(Optional.of(run(CANDIDATE_ID, metrics(0.48, 0.48, 0.48, 0.48, 3, 3))));

        ComparisonResult result = service.compare(BASELINE_ID, CANDIDATE_ID);

        assertThat(result.verdict()).isEqualTo(GateVerdict.PASS);
        assertThat(result.failedChecks()).isEmpty();
        assertThat(result.metrics()).hasSize(8);
    }

    @Test
    void failsWhenMrrRegressesBeyondThreshold() {
        when(runs.findById(BASELINE_ID)).thenReturn(Optional.of(run(BASELINE_ID, metrics(0.5, 0.5, 0.5, 0.5, 3, 3))));
        when(runs.findById(CANDIDATE_ID)).thenReturn(Optional.of(run(CANDIDATE_ID, metrics(0.44, 0.5, 0.5, 0.5, 3, 3))));

        ComparisonResult result = service.compare(BASELINE_ID, CANDIDATE_ID);

        assertThat(result.verdict()).isEqualTo(GateVerdict.FAIL);
        assertThat(result.failedChecks()).extracting(c -> c.metric()).containsExactly("MRR");
    }

    @Test
    void incompleteWhenCompletedCountDiffersFromCaseCount() {
        when(runs.findById(BASELINE_ID)).thenReturn(Optional.of(run(BASELINE_ID, metrics(0.5, 0.5, 0.5, 0.5, 3, 3))));
        when(runs.findById(CANDIDATE_ID)).thenReturn(Optional.of(run(CANDIDATE_ID, metrics(0.5, 0.5, 0.5, 0.5, 3, 2))));

        ComparisonResult result = service.compare(BASELINE_ID, CANDIDATE_ID);

        assertThat(result.verdict()).isEqualTo(GateVerdict.INCOMPLETE);
        assertThat(result.failedChecks()).isEmpty();
    }

    @Test
    void thresholdBoundaryAtExactlyTenPercentIsAllowed() {
        when(runs.findById(BASELINE_ID)).thenReturn(Optional.of(run(BASELINE_ID, metrics(0.5, 0.5, 0.5, 0.5, 1, 1))));
        // 0.45 = 0.5 * 0.90（恰好下降 10%，未超过）
        when(runs.findById(CANDIDATE_ID)).thenReturn(Optional.of(run(CANDIDATE_ID, metrics(0.45, 0.5, 0.5, 0.5, 1, 1))));

        ComparisonResult result = service.compare(BASELINE_ID, CANDIDATE_ID);

        assertThat(result.verdict()).isEqualTo(GateVerdict.PASS);
    }
}
