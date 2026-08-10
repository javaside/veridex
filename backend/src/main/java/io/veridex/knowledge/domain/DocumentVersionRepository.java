package io.veridex.knowledge.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DocumentVersionRepository extends CrudRepository<DocumentVersion, UUID> {

    List<DocumentVersion> findByDocumentIdOrderByVersionNoDesc(UUID documentId);

    long countByDocumentId(UUID documentId);

    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNoDesc(UUID documentId);
}
