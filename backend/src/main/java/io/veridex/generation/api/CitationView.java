package io.veridex.generation.api;

import java.util.UUID;

/**
 * 回答中的一个引用（[n]）及其校验状态。
 */
public record CitationView(int citationIndex, UUID documentVersionId, int chunkIndex,
                           String sourceLocation, String citationText, String validationStatus) {
}
