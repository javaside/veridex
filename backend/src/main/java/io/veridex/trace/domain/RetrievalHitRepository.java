package io.veridex.trace.domain;

import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface RetrievalHitRepository extends CrudRepository<RetrievalHit, UUID> {
}
