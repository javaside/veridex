package io.veridex.knowledge.api;

import io.veridex.iam.api.Role;
import io.veridex.iam.api.SecurityContextRole;
import io.veridex.knowledge.domain.GrantLevel;
import io.veridex.knowledge.domain.KnowledgeBaseGrantRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 知识库授权门面（knowledge.api 子包）：授权判断横跨 knowledge（grant 表）与
 * iam（Role），按 Modulith 规则只能放在 api 子包（api 可引用其他模块的 api）。
 * knowledge 的 application 子包通过本类间接使用 iam 的类型。
 */
@Component
public class KnowledgeBaseAuthorization {

    private final KnowledgeBaseGrantRepository grants;

    public KnowledgeBaseAuthorization(KnowledgeBaseGrantRepository grants) {
        this.grants = grants;
    }

    public boolean canManage(UUID kbId, UUID userId) {
        return canManage(kbId, userId, SecurityContextRole.currentRole());
    }

    public boolean canView(UUID kbId, UUID userId) {
        return canView(kbId, userId, SecurityContextRole.currentRole());
    }

    public boolean isAdmin() {
        Role role = SecurityContextRole.currentRole();
        return role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN;
    }

    public boolean canManage(UUID kbId, UUID userId, Role role) {
        if (role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN) {
            return true;
        }
        return grants.findByKnowledgeBaseIdAndUserId(kbId, userId)
                .map(g -> g.getLevel() == GrantLevel.MANAGE)
                .orElse(false);
    }

    public boolean canView(UUID kbId, UUID userId, Role role) {
        if (role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN) {
            return true;
        }
        return grants.findByKnowledgeBaseIdAndUserId(kbId, userId).isPresent();
    }
}
