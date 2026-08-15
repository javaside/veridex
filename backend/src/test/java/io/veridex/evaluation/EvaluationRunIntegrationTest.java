package io.veridex.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.evaluation.application.EvaluationDatasetService;
import io.veridex.evaluation.domain.CaseMetrics;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.EvaluationDataset;
import io.veridex.evaluation.domain.EvaluationRun;
import io.veridex.evaluation.domain.EvaluationRunCase;
import io.veridex.evaluation.domain.EvaluationRunCaseRepository;
import io.veridex.evaluation.domain.EvaluationRunRepository;
import io.veridex.evaluation.domain.ExpectedBehavior;
import io.veridex.evaluation.domain.RunMetrics;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

class EvaluationRunIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    EvaluationDatasetService datasetService;
    @Autowired
    EvaluationRunRepository runs;
    @Autowired
    EvaluationRunCaseRepository runCases;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void persistsRunAndRunCase() throws Exception {
        EvaluationDataset dataset = datasetService.create(ACTOR, "回归集", null);
        datasetService.addCase(dataset.getId(), "请假几天", ExpectedBehavior.REFUSE, null, List.of());
        DatasetVersion version = datasetService.publish(dataset.getId(), ACTOR);

        UUID profileId = UUID.randomUUID();
        EvaluationRun run = runs.save(new EvaluationRun(dataset.getId(), version.getId(), profileId,
                1, List.of(), ACTOR));
        run.complete(new JsonMapper().writeValueAsString(
                new RunMetrics(0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 1.0, 10, 1, 1)));
        runs.save(run);

        runCases.save(new EvaluationRunCase(run.getId(), 1, "请假几天", "REFUSE",
                "[]", "REFUSE", null, "[]", "[]",
                new JsonMapper().writeValueAsString(
                        new CaseMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, true, 5))));

        EvaluationRun loaded = runs.findById(run.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(EvaluationRun.Status.COMPLETED);
        assertThat(loaded.getProfileVersionNo()).isEqualTo(1);
        assertThat(loaded.getMetricsJson()).contains("\"caseCount\": 1");

        List<EvaluationRunCase> cases = runCases.findByRunIdOrderByCasePositionAsc(run.getId());
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).getActualBehavior()).isEqualTo("REFUSE");
        assertThat(cases.get(0).getMetricsJson()).contains("\"refusalMatch\": true");
    }
}
