package io.veridex.indexing.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 索引发布的只读查询门面（供 retrieval 等模块跨模块调用，不泄漏 indexing.domain 类型）。
 */
public interface IndexReleaseQuery {

    Optional<ActiveRelease> findActiveRelease(UUID knowledgeBaseId);

    List<UUID> listSnapshotDocumentVersionIds(UUID releaseId);

    record ActiveRelease(UUID releaseId, String aliasName) {
    }
}
