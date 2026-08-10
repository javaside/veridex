package io.veridex.indexing.api;

import java.util.UUID;

/**
 * 已创建待发布的 IndexRelease 草稿（api DTO，不泄漏 indexing.domain 类型）。
 */
public record DraftRelease(UUID releaseId, String indexName, String aliasName) {
}
