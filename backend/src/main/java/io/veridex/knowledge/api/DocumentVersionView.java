package io.veridex.knowledge.api;

import io.veridex.knowledge.domain.DocumentVersion;
import java.util.UUID;

public record DocumentVersionView(UUID id, UUID documentId, int versionNo, String status,
                                  int chunkCount, String errorMessage, String objectKey) {

    public static DocumentVersionView from(DocumentVersion v) {
        return new DocumentVersionView(v.getId(), v.getDocumentId(), v.getVersionNo(), v.getStatus().name(),
                v.getChunkCount(), v.getErrorMessage(), v.getObjectKey());
    }
}
