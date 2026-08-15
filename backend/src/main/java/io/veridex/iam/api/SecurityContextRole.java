package io.veridex.iam.api;

import io.veridex.iam.infrastructure.PlatformUserDetails;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityContextRole {

    private SecurityContextRole() {
    }

    public static Role currentRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Role.EMPLOYEE;
        }
        if (auth.getPrincipal() instanceof PlatformUserDetails details) {
            return details.user().getRole();
        }
        return Role.EMPLOYEE;
    }
}
