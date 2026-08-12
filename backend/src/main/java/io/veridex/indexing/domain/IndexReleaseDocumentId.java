package io.veridex.indexing.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class IndexReleaseDocumentId implements Serializable {

    private UUID releaseId;
    private UUID documentVersionId;

    protected IndexReleaseDocumentId() {
    }

    public IndexReleaseDocumentId(UUID releaseId, UUID documentVersionId) {
        this.releaseId = releaseId;
        this.documentVersionId = documentVersionId;
    }

    public UUID getReleaseId() { return releaseId; }
    public UUID getDocumentVersionId() { return documentVersionId; }

    public void setReleaseId(UUID releaseId) { this.releaseId = releaseId; }
    public void setDocumentVersionId(UUID documentVersionId) { this.documentVersionId = documentVersionId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IndexReleaseDocumentId that)) return false;
        return Objects.equals(releaseId, that.releaseId)
                && Objects.equals(documentVersionId, that.documentVersionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(releaseId, documentVersionId);
    }
}
