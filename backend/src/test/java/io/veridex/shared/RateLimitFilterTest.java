package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.RateLimitFilter;
import io.veridex.shared.infrastructure.RateLimitProperties;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksAfterLimitWithinSameMinute() throws Exception {
        RateLimitFilter filter = filterWithLimit(3);
        authenticateAs("employee");

        for (int i = 0; i < 3; i++) {
            assertThat(run(filter).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse blocked = run(filter);
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).matches("[1-9]|[1-5][0-9]|60");
        assertThat(blocked.getContentType()).isEqualTo("application/json");
        assertThat(blocked.getContentAsString()).isEqualTo("{\"error\":\"rate_limited\"}");
    }

    @Test
    void zeroDisablesLimit() throws Exception {
        RateLimitFilter filter = filterWithLimit(0);
        authenticateAs("employee");

        for (int i = 0; i < 5; i++) {
            assertThat(run(filter).getStatus()).isEqualTo(200);
        }
    }

    @Test
    void countsAuthenticationTypesWithSameNameInOneUserBucket() throws Exception {
        RateLimitFilter filter = filterWithLimit(1);
        authenticateAs("employee");
        assertThat(run(filter).getStatus()).isEqualTo(200);

        authenticateAs("employee");
        assertThat(run(filter).getStatus()).isEqualTo(429);
    }

    @Test
    void keepsDifferentUsersInSeparateBuckets() throws Exception {
        RateLimitFilter filter = filterWithLimit(1);
        authenticateAs("employee");
        assertThat(run(filter).getStatus()).isEqualTo(200);

        authenticateAs("admin");
        assertThat(run(filter).getStatus()).isEqualTo(200);
    }

    @Test
    void discardsPreviousCountersWhenWindowExpires() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-16T00:00:30Z"));
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPerMinute(1);
        RateLimitFilter filter = new RateLimitFilter(properties, clock);
        authenticateAs("employee");

        assertThat(run(filter).getStatus()).isEqualTo(200);
        assertThat(run(filter).getStatus()).isEqualTo(429);

        clock.setInstant(Instant.parse("2026-08-16T00:01:00Z"));
        assertThat(run(filter).getStatus()).isEqualTo(200);
    }

    @Test
    void incrementsConcurrentRequestsWithoutLosingUpdates() throws Exception {
        RateLimitFilter filter = filterWithLimit(25);
        Callable<Integer> request = () -> {
            authenticateAs("employee");
            try {
                return run(filter).getStatus();
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> requests = java.util.stream.IntStream.range(0, 50)
                    .mapToObj(ignored -> request)
                    .toList();
            var responses = executor.invokeAll(requests);
            long allowed = responses.stream().filter(result -> get(result) == 200).count();
            long blocked = responses.stream().filter(result -> get(result) == 429).count();

            assertThat(allowed).isEqualTo(25);
            assertThat(blocked).isEqualTo(25);
        }
    }

    private RateLimitFilter filterWithLimit(int limit) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPerMinute(limit);
        return new RateLimitFilter(properties);
    }

    private MockHttpServletResponse run(RateLimitFilter filter) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response,
                (request, result) -> ((jakarta.servlet.http.HttpServletResponse) result).setStatus(200));
        return response;
    }

    private void authenticateAs(String username) {
        var token = new TestingAuthenticationToken(username, "credentials");
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private int get(java.util.concurrent.Future<Integer> result) {
        try {
            return result.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
