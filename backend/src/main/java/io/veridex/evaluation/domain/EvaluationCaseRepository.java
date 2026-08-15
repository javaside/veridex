package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface EvaluationCaseRepository extends CrudRepository<EvaluationCase, UUID> {
    List<EvaluationCase> findByDatasetIdOrderByCreatedAtAsc(UUID datasetId);
    long countByDatasetId(UUID datasetId);
}
