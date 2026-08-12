package io.veridex.knowledge.application;

import io.veridex.knowledge.api.KnowledgeBaseAuthorization;
import io.veridex.knowledge.api.KnowledgeScopeQuery;
import io.veridex.knowledge.domain.KnowledgeBase;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeScopeQueryImpl implements KnowledgeScopeQuery {

    private final KnowledgeBaseService knowledgeBases;
    private final KnowledgeBaseAuthorization authorization;

    public KnowledgeScopeQueryImpl(KnowledgeBaseService knowledgeBases,
                                   KnowledgeBaseAuthorization authorization) {
        this.knowledgeBases = knowledgeBases;
        this.authorization = authorization;
    }

    @Override
    public List<UUID> resolve(UUID userId, List<UUID> requestedKnowledgeBaseIds) {
        Set<UUID> viewable = knowledgeBases.listViewable(userId).stream()
                .map(KnowledgeBase::getId)
                .collect(Collectors.toSet());
        return requestedKnowledgeBaseIds.stream()
                .filter(viewable::contains)
                .filter(kbId -> authorization.canView(kbId, userId))
                .toList();
    }
}
