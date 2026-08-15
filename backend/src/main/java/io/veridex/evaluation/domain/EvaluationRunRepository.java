package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface EvaluationRunRepository extends CrudRepository<EvaluationRun, UUID> {
    List<EvaluationRun> findByDatasetIdOrderByCreatedAtDesc(UUID datasetId);
}
