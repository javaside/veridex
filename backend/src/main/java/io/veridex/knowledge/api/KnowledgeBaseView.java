package io.veridex.knowledge.api;

import io.veridex.knowledge.domain.KnowledgeBase;
import java.util.UUID;

public record KnowledgeBaseView(UUID id, String name, String slug, String description, String status) {

    public static KnowledgeBaseView from(KnowledgeBase kb) {
        return new KnowledgeBaseView(kb.getId(), kb.getName(), kb.getSlug(), kb.getDescription(),
                kb.getStatus().name());
    }
}
