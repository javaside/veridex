package io.veridex.knowledge.api;

import java.util.UUID;

/**
 * 文档版本处理端口（供 ingestion worker 跨模块调用）。
 * 只暴露状态名（String），不泄漏 knowledge.domain 类型。
 */
public interface DocumentVersionProcessing {

    void markProcessing(UUID versionId);

    void markReady(UUID versionId, int chunkCount);

    void markFailed(UUID versionId, String reason);

    void setParsedObjectKey(UUID versionId, String key);

    String findVersionStatus(UUID versionId);
}
