package io.veridex.indexing.api;

import java.util.List;
import java.util.UUID;

/**
 * IndexRelease 生命周期端口（供 ingestion worker / controller 跨模块调用）。
 * 不暴露 indexing.domain 类型。
 */
public interface IndexReleaseManager {

    DraftRelease createDraft(UUID knowledgeBaseId, String aliasName);

    void prepare(UUID releaseId);

    void publish(UUID releaseId);

    void discardDraft(UUID releaseId);

    void makeCurrent(UUID knowledgeBaseId, UUID releaseId);

    void offline(UUID knowledgeBaseId, UUID releaseId);

    void delete(UUID knowledgeBaseId, UUID releaseId);

    List<ReleaseView> listReleases(UUID knowledgeBaseId);

    void setStats(UUID releaseId, int documentCount, int chunkCount);

    void markSnapshotDocuments(UUID releaseId, List<UUID> documentVersionIds);
}
