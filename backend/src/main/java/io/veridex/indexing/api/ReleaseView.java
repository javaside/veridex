package io.veridex.indexing.api;

import java.util.UUID;

/**
 * IndexRelease 对外视图（api DTO）。
 */
public record ReleaseView(UUID releaseId, int versionNo, String status, String indexName, String aliasName,
                          boolean isActive, int documentCount, int chunkCount) {
}
