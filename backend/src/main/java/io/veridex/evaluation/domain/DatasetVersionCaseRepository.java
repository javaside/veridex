package io.veridex.evaluation.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DatasetVersionCaseRepository extends CrudRepository<DatasetVersionCase, UUID> {
    List<DatasetVersionCase> findByVersionIdOrderByPositionAsc(UUID versionId);
}
