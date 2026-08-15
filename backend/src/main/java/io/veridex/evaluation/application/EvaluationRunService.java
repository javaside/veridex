package io.veridex.evaluation.application;

import io.veridex.configuration.api.ConfigurationProfileQuery;
import io.veridex.configuration.api.ProfileConfig;
import io.veridex.configuration.api.RetrievalConfig;
import io.veridex.evaluation.api.RetrievedChunkRecord;
import io.veridex.evaluation.api.RunCitationRecord;
import io.veridex.evaluation.domain.CaseMetrics;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.DatasetVersionCaseRepository;
import io.veridex.evaluation.domain.DatasetVersionRepository;
import io.veridex.evaluation.domain.EvaluationRun;
import io.veridex.evaluation.domain.EvaluationRunCase;
import io.veridex.evaluation.domain.EvaluationRunCaseRepository;
import io.veridex.evaluation.domain.EvaluationRunRepository;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.evaluation.domain.ExpectedBehavior;
import io.veridex.evaluation.domain.RunMetrics;
import io.veridex.generation.api.CitationView;
import io.veridex.generation.api.GenerationParameters;
import io.veridex.generation.api.GenerationResult;
import io.veridex.generation.api.GenerationService;
import io.veridex.retrieval.api.HybridSearchResult;
import io.veridex.retrieval.api.HybridSearchService;
import io.veridex.retrieval.api.RankedHitView;
import io.veridex.retrieval.api.RetrievalParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class EvaluationRunService {

    private final DatasetVersionRepository versions;
    private final DatasetVersionCaseRepository versionCases;
    private final EvaluationRunRepository runs;
    private final EvaluationRunCaseRepository runCases;
    private final HybridSearchService hybridSearch;
    private final GenerationService generation;
    private final ConfigurationProfileQuery configurationProfile;
    private final MetricsCalculator metrics;
    private final JsonMapper jsonMapper;

    public EvaluationRunService(DatasetVersionRepository versions,
                                DatasetVersionCaseRepository versionCases,
                                EvaluationRunRepository runs,
                                EvaluationRunCaseRepository runCases,
                                HybridSearchService hybridSearch,
                                GenerationService generation,
                                ConfigurationProfileQuery configurationProfile,
                                MetricsCalculator metrics,
                                JsonMapper jsonMapper) {
        this.versions = versions;
        this.versionCases = versionCases;
        this.runs = runs;
        this.runCases = runCases;
        this.hybridSearch = hybridSearch;
        this.generation = generation;
        this.configurationProfile = configurationProfile;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
    }

    public EvaluationRun start(UUID datasetId, int versionNo, UUID profileId, int profileVersionNo,
                               List<UUID> knowledgeBaseIds, UUID actorId) {
        DatasetVersion version = versions.findByDatasetIdAndVersionNo(datasetId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset version " + versionNo));
        ProfileConfig config = configurationProfile.requireVersionConfig(profileId, profileVersionNo);

        EvaluationRun run = runs.save(new EvaluationRun(datasetId, version.getId(), profileId,
                profileVersionNo, knowledgeBaseIds, actorId));
        try {
            List<DatasetVersionCase> cases = versionCases.findByVersionIdOrderByPositionAsc(version.getId());
            List<CaseMetrics> caseMetrics = new ArrayList<>();
            for (DatasetVersionCase c : cases) {
                long startNs = System.nanoTime();
                HybridSearchResult searchResult = hybridSearch.search(actorId, knowledgeBaseIds, knowledgeBaseIds,
                        c.getQuestion(), toRetrieval(config.retrieval()));
                GenerationResult generationResult = generation.generate(c.getQuestion(), searchResult.evidence(),
                        List.of(), toGeneration(config));
                long latencyMs = (System.nanoTime() - startNs) / 1_000_000;

                List<EvidenceRef> groundTruth = deserializeEvidence(c.getEvidenceJson());
                boolean expectedRefuse = c.getExpectedBehavior() == ExpectedBehavior.REFUSE;
                boolean actualRefuse = generationResult.refusalReason() != null;
                CaseMetrics cm = metrics.compute(groundTruth, searchResult.hits(),
                        generationResult.citations(), expectedRefuse, actualRefuse, latencyMs);
                caseMetrics.add(cm);

                runCases.save(new EvaluationRunCase(run.getId(), c.getPosition(), c.getQuestion(),
                        c.getExpectedBehavior().name(), c.getEvidenceJson(),
                        actualRefuse ? "REFUSE" : "ANSWER", generationResult.answer(),
                        serializeCitations(generationResult.citations()),
                        serializeRetrievedChunks(searchResult.hits()),
                        writeJson(cm)));
            }
            RunMetrics rm = metrics.aggregate(caseMetrics);
            run.complete(writeJson(rm));
            return runs.save(run);
        } catch (RuntimeException e) {
            run.fail(e.getMessage() == null ? "evaluation failed" : e.getMessage());
            return runs.save(run);
        }
    }

    private static RetrievalParameters toRetrieval(RetrievalConfig c) {
        return new RetrievalParameters(c.topKPerChannel(), c.rrfK(), c.contextTopK(),
                c.perDocumentMax(), c.contextMaxChars());
    }

    private static GenerationParameters toGeneration(ProfileConfig c) {
        return new GenerationParameters(c.generation().minEvidenceChars(),
                c.prompt().systemTemplate(), c.model().chatModel());
    }

    private List<EvidenceRef> deserializeEvidence(String json) {
        try {
            return jsonMapper.readValue(json, new TypeReference<List<EvidenceRef>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize ground truth evidence", e);
        }
    }

    private String serializeCitations(List<CitationView> citations) {
        List<RunCitationRecord> records = citations.stream()
                .map(c -> new RunCitationRecord(c.citationIndex(), c.documentVersionId(),
                        c.chunkIndex(), c.validationStatus()))
                .toList();
        return writeJson(records);
    }

    private String serializeRetrievedChunks(List<RankedHitView> hits) {
        List<RetrievedChunkRecord> records = hits.stream()
                .map(h -> new RetrievedChunkRecord(h.documentVersionId(), h.chunkIndex(), h.rank(), h.fusionScore()))
                .toList();
        return writeJson(records);
    }

    private String writeJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize evaluation result", e);
        }
    }
}
