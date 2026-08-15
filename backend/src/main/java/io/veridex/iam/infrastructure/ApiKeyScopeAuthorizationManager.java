package io.veridex.iam.infrastructure;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.iam.domain.ApiKeyScope;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * scope 鉴权：API key 请求必须至少一个 scope 覆盖 method+path；
 * Session 请求沿用原角色体系（controller 内部判断），一律放行到 controller。
 */
@Component
public class ApiKeyScopeAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    public AuthorizationResult authorizeNonApiKey(
            java.util.function.Supplier<? extends Authentication> authenticationSupplier,
            RequestAuthorizationContext context) {
        return new AuthorizationDecision(
                !(authenticationSupplier.get() instanceof ApiKeyAuthFilter.ApiKeyAuthentication));
    }

    public AuthorizationResult authorizeOptions(
            java.util.function.Supplier<? extends Authentication> authenticationSupplier,
            RequestAuthorizationContext context) {
        Authentication authentication = authenticationSupplier.get();
        if (authentication instanceof ApiKeyAuthFilter.ApiKeyAuthentication) {
            return authorize(authenticationSupplier, context);
        }
        return new AuthorizationDecision(true);
    }

    public AuthorizationResult authorizeAdminSession(
            java.util.function.Supplier<? extends Authentication> authenticationSupplier,
            RequestAuthorizationContext context) {
        Authentication authentication = authenticationSupplier.get();
        boolean adminSession = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof ApiKeyAuthFilter.ApiKeyAuthentication)
                && authentication.getAuthorities().stream().anyMatch(authority ->
                        "ROLE_PLATFORM_ADMIN".equals(authority.getAuthority())
                                || "ROLE_KNOWLEDGE_ADMIN".equals(authority.getAuthority()));
        return new AuthorizationDecision(adminSession);
    }

    @Override
    public AuthorizationResult authorize(java.util.function.Supplier<? extends Authentication> authenticationSupplier,
                                         RequestAuthorizationContext context) {
        Authentication authentication = authenticationSupplier.get();
        if (authentication == null || !authentication.isAuthenticated()) {
            return new AuthorizationDecision(false);
        }
        if (!(authentication instanceof ApiKeyAuthFilter.ApiKeyAuthentication keyAuthentication)) {
            return new AuthorizationDecision(true);
        }
        HttpServletRequest request = context.getRequest();
        ApiKeyService.AuthenticatedKey authenticatedKey = keyAuthentication.getCredentials();
        boolean allowed = ApiKeyScope.parse(authenticatedKey.key().getScopes()).stream()
                .anyMatch(scope -> scope.allows(request.getMethod(), request.getRequestURI()));
        return new AuthorizationDecision(allowed);
    }
}
