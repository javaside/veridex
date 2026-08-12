package io.veridex.indexing.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface IndexReleaseDocumentRepository extends CrudRepository<IndexReleaseDocument, IndexReleaseDocumentId> {

    List<IndexReleaseDocument> findByReleaseId(UUID releaseId);
}
