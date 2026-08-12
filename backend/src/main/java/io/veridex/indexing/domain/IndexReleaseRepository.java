package io.veridex.indexing.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

public interface IndexReleaseRepository extends CrudRepository<IndexRelease, UUID> {

    @Query("select coalesce(max(r.versionNo), 0) from IndexRelease r where r.knowledgeBaseId = :kb")
    int findMaxVersionNo(@Param("kb") UUID knowledgeBaseId);

    List<IndexRelease> findByKnowledgeBaseIdAndIsActiveTrue(UUID knowledgeBaseId);

    List<IndexRelease> findByKnowledgeBaseIdAndStatusOrderByVersionNoDesc(
            UUID knowledgeBaseId, IndexReleaseStatus status);

    List<IndexRelease> findByKnowledgeBaseIdOrderByVersionNoDesc(UUID knowledgeBaseId);

    @Query("select r from IndexRelease r where r.knowledgeBaseId = :kb and r.status = 'PUBLISHED' and r.id <> :exclude order by r.publishedAt desc")
    List<IndexRelease> findOtherPublished(@Param("kb") UUID kb, @Param("exclude") UUID exclude);
}
