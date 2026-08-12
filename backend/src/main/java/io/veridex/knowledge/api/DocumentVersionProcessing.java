package io.veridex.knowledge.api;

import java.util.List;
import java.util.UUID;

/**
 * 文档版本处理端口（供 ingestion worker / indexing 跨模块调用）。
 * 只暴露状态名（String）与轻量记录，不泄漏 knowledge.domain 类型。
 */
public interface DocumentVersionProcessing {

    record ReadyVersion(UUID versionId, String objectKey, int chunkCount) {}

    void markProcessing(UUID versionId);

    void markReady(UUID versionId, int chunkCount);

    void markFailed(UUID versionId, String reason);

    void setParsedObjectKey(UUID versionId, String key);

    String findVersionStatus(UUID versionId);

    List<ReadyVersion> listReadyVersions(UUID knowledgeBaseId);

    int countNotReady(UUID knowledgeBaseId);
}
