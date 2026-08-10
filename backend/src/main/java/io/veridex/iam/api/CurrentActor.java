package io.veridex.iam.api;

import io.veridex.iam.infrastructure.PlatformUserDetails;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户上下文（iam 模块公开 API）。controller 层用它取 actorId/role。
 */
public final class CurrentActor {

    private CurrentActor() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof PlatformUserDetails details) {
            return details.user().getId();
        }
        throw new IllegalStateException("no authenticated user in security context");
    }

    public static Role role() {
        return SecurityContextRole.currentRole();
    }
}
