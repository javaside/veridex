package io.veridex.evaluation.api;

import io.veridex.evaluation.application.EvaluationRunService;
import io.veridex.evaluation.domain.CaseMetrics;
import io.veridex.evaluation.domain.EvaluationRun;
import io.veridex.evaluation.domain.EvaluationRunCase;
import io.veridex.evaluation.domain.EvaluationRunCaseRepository;
import io.veridex.evaluation.domain.EvaluationRunRepository;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.evaluation.domain.RunMetrics;
import io.veridex.iam.api.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/evaluation/runs")
public class EvaluationRunController {

    private final EvaluationRunService service;
    private final EvaluationRunRepository runs;
    private final EvaluationRunCaseRepository runCases;
    private final EvaluationAuthorization authorization;
    private final JsonMapper jsonMapper;

    public EvaluationRunController(EvaluationRunService service, EvaluationRunRepository runs,
                                   EvaluationRunCaseRepository runCases,
                                   EvaluationAuthorization authorization, JsonMapper jsonMapper) {
        this.service = service;
        this.runs = runs;
        this.runCases = runCases;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public ResponseEntity<RunView> start(@RequestBody StartRunRequest body) {
        requireAdmin();
        EvaluationRun run = service.start(body.datasetId(), body.datasetVersionNo(), body.profileId(),
                body.profileVersionNo(), body.knowledgeBaseIds(), CurrentActor.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(toRunView(run));
    }

    @GetMapping
    public List<RunView> list(@RequestParam(required = false) UUID datasetId) {
        requireAdmin();
        List<EvaluationRun> found = datasetId == null
                ? runs.findAllByOrderByCreatedAtDesc()
                : runs.findByDatasetIdOrderByCreatedAtDesc(datasetId);
        return found.stream().map(this::toRunView).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunDetailView> detail(@PathVariable UUID id) {
        requireAdmin();
        EvaluationRun run = runs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown evaluation run " + id));
        List<RunCaseView> cases = runCases.findByRunIdOrderByCasePositionAsc(id).stream()
                .map(this::toRunCaseView)
                .toList();
        return ResponseEntity.ok(new RunDetailView(run.getId(), run.getDatasetId(), run.getDatasetVersionId(),
                run.getProfileId(), run.getProfileVersionNo(), run.getStatus().name(),
                metricsOrNull(run), run.getError(),
                run.getCreatedAt().toString(), run.getCompletedAt() == null ? null : run.getCompletedAt().toString(),
                cases));
    }

    private void requireAdmin() {
        if (!authorization.isAdmin()) {
            throw new SecurityException("evaluation run management requires admin role");
        }
    }

    private RunView toRunView(EvaluationRun run) {
        return new RunView(run.getId(), run.getDatasetId(), run.getDatasetVersionId(), run.getProfileId(),
                run.getProfileVersionNo(), run.getStatus().name(),
                metricsOrNull(run),
                run.getCreatedAt().toString(), run.getCompletedAt() == null ? null : run.getCompletedAt().toString());
    }

    /**
     * 只有 COMPLETED 的 run 才反序列化指标；RUNNING/FAILED 的 metricsJson 为空对象 "{}"，
     * 反序列化到 primitive double 的 RunMetrics 会抛 MismatchedInputException，导致列表/详情接口 500。
     */
    private RunMetrics metricsOrNull(EvaluationRun run) {
        if (run.getStatus() != EvaluationRun.Status.COMPLETED) {
            return null;
        }
        String json = run.getMetricsJson();
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return null;
        }
        return deserialize(json, RunMetrics.class);
    }

    private RunCaseView toRunCaseView(EvaluationRunCase c) {
        return new RunCaseView(c.getCasePosition(), c.getQuestion(), c.getExpectedBehavior(),
                c.getActualBehavior(), c.getAnswer(),
                deserializeList(c.getGroundTruthEvidenceJson(), new TypeReference<List<EvidenceRef>>() {}),
                deserializeList(c.getCitationsJson(), new TypeReference<List<RunCitationRecord>>() {}),
                deserializeList(c.getRetrievedChunksJson(), new TypeReference<List<RetrievedChunkRecord>>() {}),
                deserialize(c.getMetricsJson(), CaseMetrics.class));
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return json == null || json.isBlank() ? null : jsonMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize " + type.getSimpleName(), e);
        }
    }

    private <T> List<T> deserializeList(String json, TypeReference<List<T>> type) {
        try {
            return json == null || json.isBlank() ? List.of() : jsonMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize list", e);
        }
    }
}
