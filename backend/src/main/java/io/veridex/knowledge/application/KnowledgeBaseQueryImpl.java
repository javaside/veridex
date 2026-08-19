package io.veridex.knowledge.application;

import io.veridex.knowledge.api.KnowledgeBaseQuery;
import io.veridex.knowledge.domain.KnowledgeBase;
import io.veridex.knowledge.domain.KnowledgeBaseRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class KnowledgeBaseQueryImpl implements KnowledgeBaseQuery {

    private final KnowledgeBaseRepository knowledgeBases;

    public KnowledgeBaseQueryImpl(KnowledgeBaseRepository knowledgeBases) {
        this.knowledgeBases = knowledgeBases;
    }

    @Override
    public List<UUID> findAllIds() {
        return StreamSupport.stream(knowledgeBases.findAll().spliterator(), false)
                .map(KnowledgeBase::getId)
                .toList();
    }
}
