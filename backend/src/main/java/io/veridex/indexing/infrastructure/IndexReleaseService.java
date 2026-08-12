package io.veridex.indexing.infrastructure;

import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.api.IndexReleaseManager;
import io.veridex.indexing.api.ReleaseView;
import io.veridex.indexing.application.SearchIndexGateway;
import io.veridex.indexing.domain.IndexRelease;
import io.veridex.indexing.domain.IndexReleaseDocument;
import io.veridex.indexing.domain.IndexReleaseDocumentRepository;
import io.veridex.indexing.domain.IndexReleaseRepository;
import io.veridex.indexing.domain.IndexReleaseStatus;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IndexReleaseService implements IndexReleaseManager {

    private final IndexReleaseRepository releases;
    private final IndexReleaseDocumentRepository snapshotDocuments;
    private final SearchIndexGateway gateway;
    private final OpenSearchProperties properties;

    public IndexReleaseService(IndexReleaseRepository releases,
                               IndexReleaseDocumentRepository snapshotDocuments,
                               SearchIndexGateway gateway,
                               OpenSearchProperties properties) {
        this.releases = releases;
        this.snapshotDocuments = snapshotDocuments;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public DraftRelease createDraft(UUID knowledgeBaseId, String aliasName) {
        int next = releases.findMaxVersionNo(knowledgeBaseId) + 1;
        String indexName = properties.indexPrefix() + "-" + knowledgeBaseId + "-" + next;
        IndexRelease release = new IndexRelease(knowledgeBaseId, next, indexName, aliasName);
        IndexRelease saved = releases.save(release);
        return new DraftRelease(saved.getId(), saved.getIndexName(), saved.getAliasName());
    }

    @Override
    public void prepare(UUID releaseId) {
        IndexRelease release = require(releaseId);
        gateway.createIndex(release.getIndexName(), properties.dimensions());
    }

    @Override
    public void publish(UUID releaseId) {
        IndexRelease release = require(releaseId);
        gateway.aliasTo(release.getAliasName(), release.getIndexName());
        releases.findByKnowledgeBaseIdAndIsActiveTrue(release.getKnowledgeBaseId())
                .forEach(previous -> {
                    previous.markInactive();
                    releases.save(previous);
                });
        release.markActive();
        release.publish();
        releases.save(release);
    }

    @Override
    public void discardDraft(UUID releaseId) {
        IndexRelease release = require(releaseId);
        if (release.getStatus() != IndexReleaseStatus.DRAFT) {
            return;
        }
        gateway.deleteIndex(release.getIndexName());
        releases.delete(release);
    }

    @Override
    public void makeCurrent(UUID knowledgeBaseId, UUID releaseId) {
        IndexRelease release = requireOwned(knowledgeBaseId, releaseId);
        if (release.isActive()) {
            throw new IllegalStateException("release is already the current one");
        }
        gateway.aliasTo(release.getAliasName(), release.getIndexName());
        releases.findByKnowledgeBaseIdAndIsActiveTrue(knowledgeBaseId)
                .forEach(previous -> {
                    previous.markInactive();
                    releases.save(previous);
                });
        if (release.getStatus() == IndexReleaseStatus.OFFLINE) {
            release.reactivate();
        }
        release.markActive();
        releases.save(release);
    }

    @Override
    public void offline(UUID knowledgeBaseId, UUID releaseId) {
        IndexRelease release = requireOwned(knowledgeBaseId, releaseId);
        if (release.getStatus() != IndexReleaseStatus.PUBLISHED) {
            throw new IllegalStateException("only PUBLISHED release can be taken offline");
        }
        gateway.removeAlias(release.getAliasName());
        release.markInactive();
        release.offline();
        releases.save(release);
    }

    @Override
    public void delete(UUID knowledgeBaseId, UUID releaseId) {
        IndexRelease release = requireOwned(knowledgeBaseId, releaseId);
        if (release.isActive()) {
            throw new IllegalStateException("active release must be taken offline before deletion");
        }
        gateway.deleteIndex(release.getIndexName());
        releases.delete(release);
    }

    @Override
    public List<ReleaseView> listReleases(UUID knowledgeBaseId) {
        return releases.findByKnowledgeBaseIdOrderByVersionNoDesc(knowledgeBaseId).stream()
                .map(r -> new ReleaseView(r.getId(), r.getVersionNo(), r.getStatus().name(),
                        r.getIndexName(), r.getAliasName(), r.isActive(), r.getDocumentCount(), r.getChunkCount()))
                .toList();
    }

    @Override
    public void setStats(UUID releaseId, int documentCount, int chunkCount) {
        IndexRelease release = require(releaseId);
        release.setStats(documentCount, chunkCount);
        releases.save(release);
    }

    @Override
    public void markSnapshotDocuments(UUID releaseId, List<UUID> documentVersionIds) {
        snapshotDocuments.saveAll(documentVersionIds.stream()
                .map(v -> new IndexReleaseDocument(releaseId, v))
                .toList());
    }

    private IndexRelease requireOwned(UUID knowledgeBaseId, UUID releaseId) {
        IndexRelease release = require(releaseId);
        if (!release.getKnowledgeBaseId().equals(knowledgeBaseId)) {
            throw new IllegalArgumentException("index release does not belong to knowledge base " + knowledgeBaseId);
        }
        return release;
    }

    private IndexRelease require(UUID releaseId) {
        return releases.findById(releaseId)
                .orElseThrow(() -> new IllegalArgumentException("unknown index release " + releaseId));
    }
}
