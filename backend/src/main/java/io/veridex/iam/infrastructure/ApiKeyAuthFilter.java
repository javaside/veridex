package io.veridex.iam.infrastructure;

import io.veridex.iam.application.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer API key 认证：请求头 Authorization: Bearer vd_xxx。
 * Session 已认证时不覆盖（session 优先）。认证结果带 key 的 scopes，
 * 供 ApiKeyScopeAuthorizationManager 做 scope 鉴权。
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header == null || !header.startsWith("Bearer vd_");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()
                && !(existing instanceof AnonymousAuthenticationToken)) {
            chain.doFilter(request, response);
            return;
        }

        String token = request.getHeader("Authorization").substring("Bearer ".length());
        var authenticated = apiKeyService.authenticate(token);
        if (authenticated.isEmpty()) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"invalid_api_key\"}");
            return;
        }

        PlatformUserDetails principal = new PlatformUserDetails(authenticated.get().user());
        SecurityContextHolder.getContext().setAuthentication(
                new ApiKeyAuthentication(principal, authenticated.get()));
        chain.doFilter(request, response);
    }

    public static final class ApiKeyAuthentication extends AbstractAuthenticationToken {

        private final PlatformUserDetails principal;
        private final transient ApiKeyService.AuthenticatedKey credentials;

        ApiKeyAuthentication(PlatformUserDetails principal, ApiKeyService.AuthenticatedKey credentials) {
            super(principal.getAuthorities());
            this.principal = principal;
            this.credentials = credentials;
            setAuthenticated(true);
        }

        @Override
        public ApiKeyService.AuthenticatedKey getCredentials() {
            return credentials;
        }

        @Override
        public PlatformUserDetails getPrincipal() {
            return principal;
        }
    }
}
