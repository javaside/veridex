package io.veridex.knowledge.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface KnowledgeBaseGrantRepository extends CrudRepository<KnowledgeBaseGrant, KnowledgeBaseGrantId> {

    Optional<KnowledgeBaseGrant> findByKnowledgeBaseIdAndUserId(UUID knowledgeBaseId, UUID userId);

    List<KnowledgeBaseGrant> findByKnowledgeBaseId(UUID knowledgeBaseId);

    List<KnowledgeBaseGrant> findByUserId(UUID userId);

    void deleteByKnowledgeBaseIdAndUserId(UUID knowledgeBaseId, UUID userId);
}
