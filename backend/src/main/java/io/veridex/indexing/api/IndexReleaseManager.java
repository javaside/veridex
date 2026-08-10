package io.veridex.indexing.api;

import java.util.UUID;

/**
 * IndexRelease 生命周期端口（供 ingestion worker 跨模块调用）。
 * 不暴露 indexing.domain 类型。
 */
public interface IndexReleaseManager {

    DraftRelease createDraft(UUID knowledgeBaseId, UUID documentVersionId, String aliasName);

    void publish(UUID releaseId);

    void rollback(UUID releaseId);

    void offline(UUID releaseId);

    void delete(UUID releaseId);
}
