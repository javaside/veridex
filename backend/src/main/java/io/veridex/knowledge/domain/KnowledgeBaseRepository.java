package io.veridex.knowledge.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface KnowledgeBaseRepository extends CrudRepository<KnowledgeBase, UUID> {
    Optional<KnowledgeBase> findBySlug(String slug);
}
