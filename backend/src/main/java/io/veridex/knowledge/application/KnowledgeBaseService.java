package io.veridex.knowledge.application;

import io.veridex.knowledge.api.KnowledgeBaseAuthorization;
import io.veridex.knowledge.domain.GrantLevel;
import io.veridex.knowledge.domain.KnowledgeBase;
import io.veridex.knowledge.domain.KnowledgeBaseGrant;
import io.veridex.knowledge.domain.KnowledgeBaseGrantRepository;
import io.veridex.knowledge.domain.KnowledgeBaseRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBases;
    private final KnowledgeBaseGrantRepository grants;
    private final KnowledgeBaseAuthorization authorization;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases,
                                KnowledgeBaseGrantRepository grants,
                                KnowledgeBaseAuthorization authorization) {
        this.knowledgeBases = knowledgeBases;
        this.grants = grants;
        this.authorization = authorization;
    }

    public KnowledgeBase createKnowledgeBase(UUID actorId, String name, String description) {
        // slug：拉丁字母/数字部分 + 短随机后缀，保证唯一（中文名被清空时仍唯一）
        String base = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        String random = java.util.UUID.randomUUID().toString().substring(0, 8);
        String slug = (base.isBlank() ? "kb" : base) + "-" + random;
        KnowledgeBase kb = new KnowledgeBase(name.trim(), slug, description, actorId);
        knowledgeBases.save(kb);
        grants.save(new KnowledgeBaseGrant(kb.getId(), actorId, GrantLevel.MANAGE));
        return kb;
    }

    public void grantAccess(UUID kbId, UUID userId, GrantLevel level) {
        grants.save(new KnowledgeBaseGrant(kbId, userId, level));
    }

    public void revokeAccess(UUID kbId, UUID userId) {
        grants.deleteByKnowledgeBaseIdAndUserId(kbId, userId);
    }

    public boolean canManage(UUID kbId, UUID userId) {
        return authorization.canManage(kbId, userId);
    }

    public boolean canView(UUID kbId, UUID userId) {
        return authorization.canView(kbId, userId);
    }

    public List<KnowledgeBase> listViewable(UUID userId) {
        List<UUID> granted = grants.findByUserId(userId).stream()
                .map(KnowledgeBaseGrant::getKnowledgeBaseId)
                .toList();
        if (authorization.isAdmin()) {
            return (List<KnowledgeBase>) knowledgeBases.findAll();
        }
        return granted.stream()
                .map(id -> knowledgeBases.findById(id).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public KnowledgeBase findByIdOrThrow(UUID kbId) {
        return knowledgeBases.findById(kbId)
                .orElseThrow(() -> new IllegalArgumentException("unknown knowledge base " + kbId));
    }
}
