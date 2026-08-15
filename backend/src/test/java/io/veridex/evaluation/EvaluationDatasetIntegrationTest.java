package io.veridex.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.evaluation.application.EvaluationDatasetService;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.EvaluationCase;
import io.veridex.evaluation.domain.EvaluationDataset;
import io.veridex.evaluation.domain.ExpectedBehavior;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class EvaluationDatasetIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    EvaluationDatasetService service;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final EvidenceRef EVIDENCE = new EvidenceRef(UUID.randomUUID(), List.of(0, 1));

    @Test
    void publishFreezesWorkSetAndIncrementsVersionNo() {
        EvaluationDataset dataset = service.create(ACTOR, "回归集", "描述");
        service.addCase(dataset.getId(), "问题一", ExpectedBehavior.ANSWER, "答案一", List.of(EVIDENCE));

        DatasetVersion v1 = service.publish(dataset.getId(), ACTOR);
        assertThat(v1.getVersionNo()).isEqualTo(1);
        assertThat(v1.getCaseCount()).isEqualTo(1);

        EvaluationCase c = service.listCases(dataset.getId()).get(0);
        service.updateCase(dataset.getId(), c.getId(), "问题一(改)", ExpectedBehavior.ANSWER, "答案一(改)", List.of(EVIDENCE));
        DatasetVersion v2 = service.publish(dataset.getId(), ACTOR);
        assertThat(v2.getVersionNo()).isEqualTo(2);

        List<DatasetVersionCase> v1Cases = service.listVersionCases(v1.getId());
        assertThat(v1Cases).hasSize(1);
        assertThat(v1Cases.get(0).getQuestion()).isEqualTo("问题一");
        assertThat(v1Cases.get(0).getExpectedAnswer()).isEqualTo("答案一");

        List<DatasetVersionCase> v2Cases = service.listVersionCases(v2.getId());
        assertThat(v2Cases).hasSize(1);
        assertThat(v2Cases.get(0).getQuestion()).isEqualTo("问题一(改)");
        assertThat(v2Cases.get(0).getExpectedAnswer()).isEqualTo("答案一(改)");
    }

    @Test
    void publishEmptyDatasetIsRejected() {
        EvaluationDataset dataset = service.create(ACTOR, "空集", null);
        assertThatThrownBy(() -> service.publish(dataset.getId(), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void answerCaseWithoutEvidenceIsRejected() {
        EvaluationDataset dataset = service.create(ACTOR, "回归集", null);
        assertThatThrownBy(() -> service.addCase(dataset.getId(), "问题", ExpectedBehavior.ANSWER, "答案", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }
}
