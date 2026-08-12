package io.veridex.trace.domain;

import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface CitationRepository extends CrudRepository<Citation, UUID> {
}
