package io.veridex.knowledge.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class KnowledgeBaseGrantId implements Serializable {

    private UUID knowledgeBaseId;
    private UUID userId;

    protected KnowledgeBaseGrantId() {
    }

    public KnowledgeBaseGrantId(UUID knowledgeBaseId, UUID userId) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KnowledgeBaseGrantId that)) return false;
        return Objects.equals(knowledgeBaseId, that.knowledgeBaseId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(knowledgeBaseId, userId);
    }
}
