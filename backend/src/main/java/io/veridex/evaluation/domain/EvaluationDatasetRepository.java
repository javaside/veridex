package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface EvaluationDatasetRepository extends CrudRepository<EvaluationDataset, UUID> {
    List<EvaluationDataset> findAllByOrderByCreatedAtDesc();
}
