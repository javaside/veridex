package io.veridex.evaluation.application;

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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class EvaluationDatasetService {

    private final EvaluationDatasetRepository datasets;
    private final EvaluationCaseRepository cases;
    private final DatasetVersionRepository versions;
    private final DatasetVersionCaseRepository versionCases;
    private final JsonMapper jsonMapper;

    public EvaluationDatasetService(EvaluationDatasetRepository datasets,
                                    EvaluationCaseRepository cases,
                                    DatasetVersionRepository versions,
                                    DatasetVersionCaseRepository versionCases,
                                    JsonMapper jsonMapper) {
        this.datasets = datasets;
        this.cases = cases;
        this.versions = versions;
        this.versionCases = versionCases;
        this.jsonMapper = jsonMapper;
    }

    public EvaluationDataset create(UUID actorId, String name, String description) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        return datasets.save(new EvaluationDataset(trimmed, description, actorId));
    }

    public EvaluationDataset require(UUID datasetId) {
        return datasets.findById(datasetId)
                .orElseThrow(() -> new IllegalArgumentException("unknown dataset " + datasetId));
    }

    public List<EvaluationDataset> listAll() {
        return datasets.findAllByOrderByCreatedAtDesc();
    }

    public long caseCount(UUID datasetId) {
        return cases.countByDatasetId(datasetId);
    }

    public Integer latestVersionNo(UUID datasetId) {
        return versions.findTopByDatasetIdOrderByVersionNoDesc(datasetId)
                .map(DatasetVersion::getVersionNo)
                .orElse(null);
    }

    public EvaluationCase addCase(UUID datasetId, String question, ExpectedBehavior behavior,
                                  String expectedAnswer, List<EvidenceRef> evidence) {
        require(datasetId);
        String normalizedQuestion = normalizeQuestion(question);
        List<EvidenceRef> normalizedEvidence = normalizeEvidence(behavior, evidence);
        return cases.save(new EvaluationCase(datasetId, normalizedQuestion, behavior,
                expectedAnswer, serializeEvidence(normalizedEvidence)));
    }

    public EvaluationCase updateCase(UUID datasetId, UUID caseId, String question, ExpectedBehavior behavior,
                                     String expectedAnswer, List<EvidenceRef> evidence) {
        require(datasetId);
        EvaluationCase existing = requireCase(datasetId, caseId);
        String normalizedQuestion = normalizeQuestion(question);
        List<EvidenceRef> normalizedEvidence = normalizeEvidence(behavior, evidence);
        existing.update(normalizedQuestion, behavior, expectedAnswer, serializeEvidence(normalizedEvidence));
        return cases.save(existing);
    }

    public void deleteCase(UUID datasetId, UUID caseId) {
        require(datasetId);
        cases.delete(requireCase(datasetId, caseId));
    }

    public List<EvaluationCase> listCases(UUID datasetId) {
        require(datasetId);
        return cases.findByDatasetIdOrderByCreatedAtAsc(datasetId);
    }

    public DatasetVersion publish(UUID datasetId, UUID actorId) {
        require(datasetId);
        List<EvaluationCase> workSet = cases.findByDatasetIdOrderByCreatedAtAsc(datasetId);
        if (workSet.isEmpty()) {
            throw new IllegalArgumentException("cannot publish an empty dataset");
        }
        int nextVersionNo = versions.findTopByDatasetIdOrderByVersionNoDesc(datasetId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
        DatasetVersion version = versions.save(
                new DatasetVersion(datasetId, nextVersionNo, workSet.size(), actorId));
        int position = 1;
        for (EvaluationCase c : workSet) {
            versionCases.save(new DatasetVersionCase(version.getId(), position++,
                    c.getQuestion(), c.getExpectedBehavior(), c.getExpectedAnswer(), c.getEvidenceJson()));
        }
        return version;
    }

    public List<DatasetVersion> listVersions(UUID datasetId) {
        require(datasetId);
        return versions.findByDatasetIdOrderByVersionNoDesc(datasetId);
    }

    public DatasetVersion requireVersion(UUID datasetId, int versionNo) {
        return versions.findByDatasetIdAndVersionNo(datasetId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown version " + versionNo + " for dataset " + datasetId));
    }

    public List<DatasetVersionCase> listVersionCases(UUID versionId) {
        return versionCases.findByVersionIdOrderByPositionAsc(versionId);
    }

    private EvaluationCase requireCase(UUID datasetId, UUID caseId) {
        EvaluationCase existing = cases.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("unknown case " + caseId));
        if (!existing.getDatasetId().equals(datasetId)) {
            throw new IllegalArgumentException("case " + caseId + " does not belong to dataset " + datasetId);
        }
        return existing;
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.trim().isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        return question.trim();
    }

    private List<EvidenceRef> normalizeEvidence(ExpectedBehavior behavior, List<EvidenceRef> evidence) {
        if (behavior == null) {
            throw new IllegalArgumentException("expectedBehavior is required");
        }
        List<EvidenceRef> normalized = evidence == null ? List.of() : evidence;
        if (behavior == ExpectedBehavior.ANSWER && normalized.isEmpty()) {
            throw new IllegalArgumentException("ANSWER case requires at least one evidence chunk");
        }
        if (behavior == ExpectedBehavior.REFUSE) {
            return List.of();
        }
        return normalized;
    }

    private String serializeEvidence(List<EvidenceRef> evidence) {
        try {
            return jsonMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize evidence", e);
        }
    }
}
