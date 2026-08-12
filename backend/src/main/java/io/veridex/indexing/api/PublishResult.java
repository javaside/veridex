package io.veridex.indexing.api;

/**
 * 手动发布结果：新发布的快照视图 + 本次发布未包含的文档数（PROCESSING/FAILED）。
 */
public record PublishResult(ReleaseView release, int excludedCount) {
}
