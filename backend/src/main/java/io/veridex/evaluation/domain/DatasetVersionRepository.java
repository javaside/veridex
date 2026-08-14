package io.veridex.evaluation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DatasetVersionRepository extends CrudRepository<DatasetVersion, UUID> {
    List<DatasetVersion> findByDatasetIdOrderByVersionNoDesc(UUID datasetId);
    Optional<DatasetVersion> findTopByDatasetIdOrderByVersionNoDesc(UUID datasetId);
    Optional<DatasetVersion> findByDatasetIdAndVersionNo(UUID datasetId, int versionNo);
}
