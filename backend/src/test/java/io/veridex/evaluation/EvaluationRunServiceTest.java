package io.veridex.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.veridex.configuration.api.ChunkingConfig;
import io.veridex.configuration.api.ConfigurationProfileQuery;
import io.veridex.configuration.api.GenerationConfig;
import io.veridex.configuration.api.ModelConfig;
import io.veridex.configuration.api.ProfileConfig;
import io.veridex.configuration.api.PromptConfig;
import io.veridex.configuration.api.RetrievalConfig;
import io.veridex.evaluation.application.EvaluationRunService;
import io.veridex.evaluation.application.MetricsCalculator;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.DatasetVersionCaseRepository;
import io.veridex.evaluation.domain.DatasetVersionRepository;
import io.veridex.evaluation.domain.EvaluationRun;
import io.veridex.evaluation.domain.EvaluationRunCaseRepository;
import io.veridex.evaluation.domain.EvaluationRunRepository;
import io.veridex.evaluation.domain.ExpectedBehavior;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationParameters;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.GenerationService;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.retrieval.api.HybridSearchResult;
import io.veridex.retrieval.api.HybridSearchService;
import io.veridex.retrieval.api.RankedHitView;
import io.veridex.retrieval.api.RetrievalParameters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EvaluationRunServiceTest {

    private DatasetVersionRepository versions;
    private DatasetVersionCaseRepository versionCases;
    private EvaluationRunRepository runs;
    private EvaluationRunCaseRepository runCases;
    private HybridSearchService hybridSearch;
    private GenerationService generation;
    private ConfigurationProfileQuery configurationProfile;
    private EvaluationRunService service;

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID KB = UUID.randomUUID();
    private static final UUID DOC_VERSION = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        versions = mock(DatasetVersionRepository.class);
        versionCases = mock(DatasetVersionCaseRepository.class);
        runs = mock(EvaluationRunRepository.class);
        runCases = mock(EvaluationRunCaseRepository.class);
        hybridSearch = mock(HybridSearchService.class);
        generation = mock(GenerationService.class);
        configurationProfile = mock(ConfigurationProfileQuery.class);
        service = new EvaluationRunService(versions, versionCases, runs, runCases, hybridSearch,
                generation, configurationProfile, new MetricsCalculator(), new JsonMapper());
    }

    private static ProfileConfig profileConfig() {
        return new ProfileConfig(
                new ChunkingConfig(2000, 80),
                new RetrievalConfig(30, 60, 6, 3, 4000),
                new GenerationConfig(6, 50),
                new PromptConfig("你是企业制度问答助手。\n"),
                new ModelConfig("deterministic", "deterministic"));
    }

    @Test
    void runEvaluatesEachCaseAndCompletes() {
        var datasetVersion = new DatasetVersion(DATASET, 1, 1, ACTOR);
        when(versions.findByDatasetIdAndVersionNo(DATASET, 1)).thenReturn(Optional.of(datasetVersion));
        var evidenceJson = "[{\"documentVersionId\":\"" + DOC_VERSION + "\",\"chunkIndexes\":[0]}]";
        var c = new DatasetVersionCase(VERSION, 1, "请假几天", ExpectedBehavior.ANSWER, "答案", evidenceJson);
        when(versionCases.findByVersionIdOrderByPositionAsc(any())).thenReturn(List.of(c));
        when(configurationProfile.requireVersionConfig(PROFILE, 1)).thenReturn(profileConfig());
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(runCases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var evidence = List.of(new EvidencePiece(1, KB, DOC_VERSION, 0, "t", "1", "员工请假需提前两个工作日。"));
        var hits = List.of(new RankedHitView(KB, DOC_VERSION, 0, "BM25", null, null, 1.0, 1, true, null));
        when(hybridSearch.search(eq(ACTOR), eq(List.of(KB)), eq(List.of(KB)), eq("请假几天"), any(RetrievalParameters.class)))
                .thenReturn(new HybridSearchResult(evidence, hits, List.of()));
        var citations = List.of(new CitationView(1, UUID.randomUUID(), DOC_VERSION, 0, "t", "[1]", "VALID"));
        when(generation.generate(eq("请假几天"), eq(evidence), eq(List.of()), any(GenerationParameters.class)))
                .thenReturn(new GenerationResult("根据《t》[1]，员工请假需提前两个工作日", citations, null, "deterministic", 0, 0, 0, null));

        EvaluationRun run = service.start(DATASET, 1, PROFILE, 1, List.of(KB), ACTOR);

        assertThat(run.getStatus()).isEqualTo(EvaluationRun.Status.COMPLETED);
        assertThat(run.getMetricsJson()).contains("\"caseCount\":1");
    }

    @Test
    void runFailsWhenRetrievalThrows() {
        var datasetVersion = new DatasetVersion(DATASET, 1, 1, ACTOR);
        when(versions.findByDatasetIdAndVersionNo(DATASET, 1)).thenReturn(Optional.of(datasetVersion));
        var c = new DatasetVersionCase(VERSION, 1, "请假几天", ExpectedBehavior.ANSWER, "答案", "[]");
        when(versionCases.findByVersionIdOrderByPositionAsc(any())).thenReturn(List.of(c));
        when(configurationProfile.requireVersionConfig(PROFILE, 1)).thenReturn(profileConfig());
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(hybridSearch.search(any(), any(), any(), any(), any(RetrievalParameters.class)))
                .thenThrow(new RuntimeException("opensearch down"));

        EvaluationRun run = service.start(DATASET, 1, PROFILE, 1, List.of(KB), ACTOR);

        assertThat(run.getStatus()).isEqualTo(EvaluationRun.Status.FAILED);
        assertThat(run.getError()).contains("opensearch down");
    }
}
