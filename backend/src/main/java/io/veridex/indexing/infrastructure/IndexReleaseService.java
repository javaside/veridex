package io.veridex.indexing.infrastructure;

import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.api.IndexReleaseManager;
import io.veridex.indexing.api.ReleaseView;
import io.veridex.indexing.application.SearchIndexGateway;
import io.veridex.indexing.domain.IndexRelease;
import io.veridex.indexing.domain.IndexReleaseRepository;
import io.veridex.indexing.domain.IndexReleaseStatus;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class IndexReleaseService implements IndexReleaseManager {

    private final IndexReleaseRepository releases;
    private final SearchIndexGateway gateway;
    private final OpenSearchProperties properties;

    public IndexReleaseService(IndexReleaseRepository releases, SearchIndexGateway gateway,
                               OpenSearchProperties properties) {
        this.releases = releases;
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public DraftRelease createDraft(UUID knowledgeBaseId, UUID documentVersionId, String aliasName) {
        long next = releases.countByKnowledgeBaseId(knowledgeBaseId) + 1;
        String indexName = "veridex-" + next;
        IndexRelease release = new IndexRelease(knowledgeBaseId, documentVersionId, (int) next,
                indexName, aliasName);
        IndexRelease saved = releases.save(release);
        return new DraftRelease(saved.getId(), saved.getIndexName(), saved.getAliasName());
    }

    @Override
    public void publish(UUID releaseId) {
        IndexRelease release = require(releaseId);
        gateway.createIndex(release.getIndexName(), properties.dimensions());
        gateway.aliasTo(release.getAliasName(), release.getIndexName());
        release.publish();
        releases.save(release);
    }

    @Override
    public void rollback(UUID releaseId) {
        IndexRelease release = require(releaseId);
        if (release.getStatus() != IndexReleaseStatus.PUBLISHED) {
            throw new IllegalStateException("only PUBLISHED release can be rolled back");
        }
        Optional<IndexRelease> previous = releases.findOtherPublished(release.getKnowledgeBaseId(), release.getId())
                .stream().findFirst();
        if (previous.isPresent()) {
            gateway.aliasTo(release.getAliasName(), previous.get().getIndexName());
        } else {
            gateway.removeAlias(release.getAliasName());
        }
        release.rollback();
        releases.save(release);
    }

    @Override
    public void offline(UUID releaseId) {
        IndexRelease release = require(releaseId);
        if (release.getStatus() != IndexReleaseStatus.PUBLISHED) {
            throw new IllegalStateException("only PUBLISHED release can be taken offline");
        }
        gateway.removeAlias(release.getAliasName());
        release.offline();
        releases.save(release);
    }

    @Override
    public void delete(UUID releaseId) {
        IndexRelease release = require(releaseId);
        gateway.deleteIndex(release.getIndexName());
        releases.delete(release);
    }

    @Override
    public List<ReleaseView> listReleases(UUID knowledgeBaseId) {
        return releases.findByKnowledgeBaseIdOrderByVersionNoDesc(knowledgeBaseId).stream()
                .map(r -> new ReleaseView(r.getId(), r.getVersionNo(), r.getStatus().name(),
                        r.getIndexName(), r.getAliasName()))
                .toList();
    }

    private IndexRelease require(UUID releaseId) {
        return releases.findById(releaseId)
                .orElseThrow(() -> new IllegalArgumentException("unknown index release " + releaseId));
    }
}
