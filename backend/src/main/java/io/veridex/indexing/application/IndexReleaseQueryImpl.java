package io.veridex.indexing.application;

import io.veridex.indexing.api.IndexReleaseQuery;
import io.veridex.indexing.domain.IndexReleaseDocument;
import io.veridex.indexing.domain.IndexReleaseDocumentRepository;
import io.veridex.indexing.domain.IndexReleaseRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IndexReleaseQueryImpl implements IndexReleaseQuery {

    private final IndexReleaseRepository releases;
    private final IndexReleaseDocumentRepository snapshotDocuments;

    public IndexReleaseQueryImpl(IndexReleaseRepository releases,
                                 IndexReleaseDocumentRepository snapshotDocuments) {
        this.releases = releases;
        this.snapshotDocuments = snapshotDocuments;
    }

    @Override
    public Optional<ActiveRelease> findActiveRelease(UUID knowledgeBaseId) {
        return releases.findByKnowledgeBaseIdAndIsActiveTrue(knowledgeBaseId).stream().findFirst()
                .map(r -> new ActiveRelease(r.getId(), r.getAliasName()));
    }

    @Override
    public List<UUID> listSnapshotDocumentVersionIds(UUID releaseId) {
        return snapshotDocuments.findByReleaseId(releaseId).stream()
                .map(IndexReleaseDocument::getDocumentVersionId)
                .toList();
    }
}
