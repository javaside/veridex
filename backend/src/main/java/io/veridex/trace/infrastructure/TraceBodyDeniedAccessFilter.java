package io.veridex.trace.infrastructure;

import io.veridex.iam.api.CurrentActor;
import io.veridex.shared.infrastructure.RequestIds;
import io.veridex.trace.application.TraceBodyAccessAuditor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

public class TraceBodyDeniedAccessFilter extends OncePerRequestFilter {
    private final TraceBodyAccessAuditor auditor;

    public TraceBodyDeniedAccessFilter(TraceBodyAccessAuditor auditor) {
        this.auditor = auditor;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || !request.getRequestURI().matches("/api/traces/[^/]+/body");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        int status = response.getStatus();
        boolean loginRedirect = status >= 300 && status < 400
                && response.getHeader("Location") != null
                && response.getHeader("Location").endsWith("/login");
        if (status != HttpServletResponse.SC_UNAUTHORIZED
                && status != HttpServletResponse.SC_FORBIDDEN
                && !loginRedirect) return;
        UUID actorId = currentActorId();
        UUID runId = parseRunId(request);
        String code = status == HttpServletResponse.SC_FORBIDDEN ? "FORBIDDEN" : "UNAUTHENTICATED";
        auditor.record(actorId, TraceBodyAccessAuditor.DENIED, runId, RequestIds.current(request), Map.of("code", code));
    }

    private static UUID currentActorId() {
        try {
            return CurrentActor.id();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static UUID parseRunId(HttpServletRequest request) {
        String[] parts = request.getRequestURI().split("/");
        try {
            return UUID.fromString(parts[3]);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
