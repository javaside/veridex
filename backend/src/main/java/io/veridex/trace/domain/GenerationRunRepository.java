package io.veridex.trace.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface GenerationRunRepository extends CrudRepository<GenerationRun, UUID> {

    Optional<GenerationRun> findFirstByQueryRunId(UUID queryRunId);
}
