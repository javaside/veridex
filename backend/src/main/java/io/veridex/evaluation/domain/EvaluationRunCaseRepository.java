package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface EvaluationRunCaseRepository extends CrudRepository<EvaluationRunCase, UUID> {
    List<EvaluationRunCase> findByRunIdOrderByCasePositionAsc(UUID runId);
}
