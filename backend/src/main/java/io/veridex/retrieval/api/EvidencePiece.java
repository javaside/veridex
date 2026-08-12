package io.veridex.retrieval.api;

import java.util.UUID;

/**
 * 进入上下文的证据片段。citationIndex 即 [n] 引用编号（从 1 开始）。
 */
public record EvidencePiece(int citationIndex, UUID knowledgeBaseId, UUID documentVersionId,
                            int chunkIndex, String title, String structurePath, String text) {
}
