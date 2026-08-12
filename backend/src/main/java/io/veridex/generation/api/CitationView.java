package io.veridex.generation.api;

import java.util.UUID;

/**
 * 回答中的一个引用（[n]）及其校验状态。documentId 供前端调用 chunks API 预览原文。
 */
public record CitationView(int citationIndex, UUID documentId, UUID documentVersionId, int chunkIndex,
                           String sourceLocation, String citationText, String validationStatus) {
}
