package io.veridex.knowledge.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface DocumentRepository extends CrudRepository<Document, UUID> {

    Optional<Document> findByKnowledgeBaseIdAndFilename(UUID knowledgeBaseId, String filename);

    List<Document> findByKnowledgeBaseIdOrderByCreatedAtDesc(UUID knowledgeBaseId);
}
