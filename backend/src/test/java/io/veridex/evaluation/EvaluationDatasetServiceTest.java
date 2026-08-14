package io.veridex.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.evaluation.application.EvaluationDatasetService;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.DatasetVersionCaseRepository;
import io.veridex.evaluation.domain.DatasetVersionRepository;
import io.veridex.evaluation.domain.EvaluationCase;
import io.veridex.evaluation.domain.EvaluationCaseRepository;
import io.veridex.evaluation.domain.EvaluationDataset;
import io.veridex.evaluation.domain.EvaluationDatasetRepository;
import io.veridex.evaluation.domain.ExpectedBehavior;
import io.veridex.evaluation.domain.EvidenceRef;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EvaluationDatasetServiceTest {

    private EvaluationDatasetRepository datasets;
    private EvaluationCaseRepository cases;
    private DatasetVersionRepository versions;
    private DatasetVersionCaseRepository versionCases;
    private EvaluationDatasetService service;

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();
    private static final UUID CASE = UUID.randomUUID();
    private static final EvidenceRef EVIDENCE = new EvidenceRef(UUID.randomUUID(), List.of(0, 1));

    @BeforeEach
    void setUp() {
        datasets = mock(EvaluationDatasetRepository.class);
        cases = mock(EvaluationCaseRepository.class);
        versions = mock(DatasetVersionRepository.class);
        versionCases = mock(DatasetVersionCaseRepository.class);
        service = new EvaluationDatasetService(datasets, cases, versions, versionCases, new JsonMapper());
    }

    @Test
    void createTrimsNameAndRejectsBlankName() {
        when(datasets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        EvaluationDataset created = service.create(ACTOR, "  回归集  ", "desc");
        assertThat(created.getName()).isEqualTo("回归集");
        assertThatThrownBy(() -> service.create(ACTOR, "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addAnswerCaseWithoutEvidenceIsRejected() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        assertThatThrownBy(() -> service.addCase(DATASET, "问题", ExpectedBehavior.ANSWER, "答案", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }

    @Test
    void addRefuseCaseNormalizesEvidenceToEmpty() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        when(cases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        EvaluationCase saved = service.addCase(DATASET, "问题", ExpectedBehavior.REFUSE, null, List.of(EVIDENCE));
        assertThat(saved.getEvidenceJson()).isEqualTo("[]");
    }

    @Test
    void updateCaseRejectsCaseFromAnotherDataset() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        EvaluationCase other = new EvaluationCase(UUID.randomUUID(), "q", ExpectedBehavior.ANSWER, "a", "[]");
        when(cases.findById(CASE)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.updateCase(DATASET, CASE, "q", ExpectedBehavior.ANSWER, "a", List.of(EVIDENCE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void publishRejectsEmptyWorkSet() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        when(cases.findByDatasetIdOrderByCreatedAtAsc(DATASET)).thenReturn(List.of());
        assertThatThrownBy(() -> service.publish(DATASET, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void publishFreezesWorkSetIntoVersionCases() {
        when(datasets.findById(DATASET)).thenReturn(Optional.of(new EvaluationDataset("d", null, ACTOR)));
        EvaluationCase c = new EvaluationCase(DATASET, "q", ExpectedBehavior.ANSWER, "a",
                "[{\"documentVersionId\":\"" + EVIDENCE.documentVersionId() + "\",\"chunkIndexes\":[0,1]}]");
        when(cases.findByDatasetIdOrderByCreatedAtAsc(DATASET)).thenReturn(List.of(c));
        when(versions.findTopByDatasetIdOrderByVersionNoDesc(DATASET)).thenReturn(Optional.empty());
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DatasetVersion version = service.publish(DATASET, ACTOR);

        assertThat(version.getVersionNo()).isEqualTo(1);
        assertThat(version.getCaseCount()).isEqualTo(1);
        verify(versionCases).save(argThat(vc -> vc.getPosition() == 1
                && vc.getQuestion().equals("q")
                && vc.getExpectedBehavior() == ExpectedBehavior.ANSWER));
    }
}
