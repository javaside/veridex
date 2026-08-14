package io.veridex.evaluation.api;

import io.veridex.evaluation.application.EvaluationDatasetService;
import io.veridex.evaluation.domain.DatasetVersion;
import io.veridex.evaluation.domain.DatasetVersionCase;
import io.veridex.evaluation.domain.EvaluationCase;
import io.veridex.evaluation.domain.EvaluationDataset;
import io.veridex.evaluation.domain.EvidenceRef;
import io.veridex.iam.api.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/evaluation/datasets")
public class EvaluationController {

    private final EvaluationDatasetService service;
    private final EvaluationAuthorization authorization;
    private final JsonMapper jsonMapper;

    public EvaluationController(EvaluationDatasetService service,
                                EvaluationAuthorization authorization,
                                JsonMapper jsonMapper) {
        this.service = service;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public ResponseEntity<DatasetView> create(@RequestBody CreateDatasetRequest body) {
        requireAdmin();
        EvaluationDataset dataset = service.create(CurrentActor.id(), body.name(), body.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDatasetView(dataset));
    }

    @GetMapping
    public List<DatasetView> list() {
        requireAdmin();
        return service.listAll().stream().map(this::toDatasetView).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<DatasetView> get(@PathVariable UUID id) {
        requireAdmin();
        return ResponseEntity.ok(toDatasetView(service.require(id)));
    }

    @PostMapping("/{id}/cases")
    public ResponseEntity<CaseView> addCase(@PathVariable UUID id, @RequestBody CreateCaseRequest body) {
        requireAdmin();
        EvaluationCase c = service.addCase(id, body.question(), body.expectedBehavior(),
                body.expectedAnswer(), body.evidence());
        return ResponseEntity.status(HttpStatus.CREATED).body(toCaseView(c));
    }

    @PutMapping("/{id}/cases/{caseId}")
    public ResponseEntity<CaseView> updateCase(@PathVariable UUID id, @PathVariable UUID caseId,
                                               @RequestBody UpdateCaseRequest body) {
        requireAdmin();
        EvaluationCase c = service.updateCase(id, caseId, body.question(), body.expectedBehavior(),
                body.expectedAnswer(), body.evidence());
        return ResponseEntity.ok(toCaseView(c));
    }

    @DeleteMapping("/{id}/cases/{caseId}")
    public ResponseEntity<Void> deleteCase(@PathVariable UUID id, @PathVariable UUID caseId) {
        requireAdmin();
        service.deleteCase(id, caseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/cases")
    public List<CaseView> listCases(@PathVariable UUID id) {
        requireAdmin();
        return service.listCases(id).stream().map(this::toCaseView).toList();
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<PublishResult> publish(@PathVariable UUID id) {
        requireAdmin();
        DatasetVersion version = service.publish(id, CurrentActor.id());
        return ResponseEntity.ok(new PublishResult(version.getId(), version.getVersionNo(), version.getCaseCount()));
    }

    @GetMapping("/{id}/versions")
    public List<VersionView> listVersions(@PathVariable UUID id) {
        requireAdmin();
        return service.listVersions(id).stream().map(this::toVersionView).toList();
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public ResponseEntity<VersionDetailView> version(@PathVariable UUID id, @PathVariable int versionNo) {
        requireAdmin();
        DatasetVersion version = service.requireVersion(id, versionNo);
        List<VersionCaseView> cases = service.listVersionCases(version.getId()).stream()
                .map(this::toVersionCaseView)
                .toList();
        return ResponseEntity.ok(new VersionDetailView(version.getId(), version.getVersionNo(),
                version.getCaseCount(), version.getCreatedAt().toString(), cases));
    }

    private void requireAdmin() {
        if (!authorization.isAdmin()) {
            throw new SecurityException("evaluation management requires admin role");
        }
    }

    private DatasetView toDatasetView(EvaluationDataset d) {
        return new DatasetView(d.getId(), d.getName(), d.getDescription(),
                (int) service.caseCount(d.getId()), service.latestVersionNo(d.getId()));
    }

    private CaseView toCaseView(EvaluationCase c) {
        return new CaseView(c.getId(), c.getQuestion(), c.getExpectedBehavior().name(),
                c.getExpectedAnswer(), deserializeEvidence(c.getEvidenceJson()));
    }

    private VersionView toVersionView(DatasetVersion v) {
        return new VersionView(v.getId(), v.getVersionNo(), v.getCaseCount(), v.getCreatedAt().toString());
    }

    private VersionCaseView toVersionCaseView(DatasetVersionCase c) {
        return new VersionCaseView(c.getPosition(), c.getQuestion(), c.getExpectedBehavior().name(),
                c.getExpectedAnswer(), deserializeEvidence(c.getEvidenceJson()));
    }

    private List<EvidenceRef> deserializeEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<List<EvidenceRef>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize evidence", e);
        }
    }
}
