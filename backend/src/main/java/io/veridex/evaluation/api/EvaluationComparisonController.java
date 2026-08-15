package io.veridex.evaluation.api;

import io.veridex.evaluation.application.EvaluationComparisonService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation/comparisons")
public class EvaluationComparisonController {

    private final EvaluationComparisonService service;
    private final EvaluationAuthorization authorization;

    public EvaluationComparisonController(EvaluationComparisonService service,
                                          EvaluationAuthorization authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @PostMapping
    public ComparisonResult compare(@RequestBody CompareRunsRequest body) {
        requireAdmin();
        return service.compare(body.baselineRunId(), body.candidateRunId());
    }

    private void requireAdmin() {
        if (!authorization.isAdmin()) {
            throw new SecurityException("evaluation comparison requires admin role");
        }
    }
}
