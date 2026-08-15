package io.veridex.shared.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(100)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String ANONYMOUS_KEY = "anonymous";

    private final RateLimitProperties properties;
    private final Clock clock;
    private Window currentWindow;

    @Autowired
    public RateLimitFilter(RateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public RateLimitFilter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = properties.getRateLimitPerMinute();
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }

        SampledWindow sampled = sampleWindow();
        long epochSecond = sampled.epochSecond();
        Window window = sampled.window();
        int count = window.counts().computeIfAbsent(userKey(), ignored -> new AtomicInteger()).incrementAndGet();
        if (count > limit) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", Long.toString(60 - epochSecond % 60));
            response.getWriter().write("{\"error\":\"rate_limited\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private synchronized SampledWindow sampleWindow() {
        long epochSecond = clock.instant().getEpochSecond();
        long minute = epochSecond / 60;
        if (currentWindow == null || currentWindow.minute() < minute) {
            currentWindow = new Window(minute, new ConcurrentHashMap<>());
        }
        return new SampledWindow(epochSecond, currentWindow);
    }

    private String userKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ANONYMOUS_KEY;
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? ANONYMOUS_KEY : name;
    }

    private record SampledWindow(long epochSecond, Window window) {
    }

    private record Window(long minute, ConcurrentHashMap<String, AtomicInteger> counts) {
    }
}
