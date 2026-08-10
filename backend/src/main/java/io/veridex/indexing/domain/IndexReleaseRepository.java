package io.veridex.indexing.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface IndexReleaseRepository extends CrudRepository<IndexRelease, UUID> {

    long countByKnowledgeBaseId(UUID knowledgeBaseId);

    Optional<IndexRelease> findByDocumentVersionId(UUID documentVersionId);

    List<IndexRelease> findByKnowledgeBaseIdAndStatusOrderByVersionNoDesc(
            UUID knowledgeBaseId, IndexReleaseStatus status);

    @Query("select r from IndexRelease r where r.knowledgeBaseId = :kb and r.status = 'PUBLISHED' and r.id <> :exclude order by r.publishedAt desc")
    List<IndexRelease> findOtherPublished(@Param("kb") UUID kb, @Param("exclude") UUID exclude);
}
