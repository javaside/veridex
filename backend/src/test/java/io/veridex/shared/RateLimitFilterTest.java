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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    void actuatorRequestsDoNotConsumeRateLimitBucket() throws Exception {
        RateLimitFilter filter = filterWithLimit(1);

        assertThat(run(filter, "/actuator/health").getStatus()).isEqualTo(200);
        assertThat(run(filter, "/actuator/prometheus").getStatus()).isEqualTo(200);
        assertThat(run(filter, "/api/qa/conversations").getStatus()).isEqualTo(200);
        assertThat(run(filter, "/actuator-like").getStatus()).isEqualTo(429);
        assertThat(run(filter, "/api/qa/conversations").getStatus()).isEqualTo(429);
        assertThat(run(filter, "/actuator/health").getStatus()).isEqualTo(200);
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
    void countsSameUsernameInOneUserBucket() throws Exception {
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
    void assignsDelayedOldMinuteRequestToItsSampledWindowWithoutRollingBack() throws Exception {
        InterleavingClock clock = new InterleavingClock(
                Instant.parse("2026-08-16T00:00:59Z"), Instant.parse("2026-08-16T00:01:00Z"));
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPerMinute(1);
        RateLimitFilter filter = new RateLimitFilter(properties, clock);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var oldMinuteRequest = executor.submit(() -> runAs(filter, "employee").getStatus());
            clock.awaitOldMinuteSampled();
            AtomicReference<Thread> newMinuteThread = new AtomicReference<>();
            var newMinuteRequest = executor.submit(() -> {
                newMinuteThread.set(Thread.currentThread());
                return runAs(filter, "employee").getStatus();
            });

            clock.awaitSecondRequestReadyOrClockCalled(newMinuteThread);
            clock.releaseOldMinuteRequest();
            assertThat(newMinuteRequest.get(5, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(oldMinuteRequest.get(5, TimeUnit.SECONDS)).isEqualTo(200);

            assertThat(runAs(filter, "employee").getStatus()).isEqualTo(429);
        } finally {
            clock.releaseOldMinuteRequest();
        }
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
        return run(filter, "/api/test");
    }

    private MockHttpServletResponse run(RateLimitFilter filter, String requestUri)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", requestUri), response,
                (request, result) -> ((jakarta.servlet.http.HttpServletResponse) result).setStatus(200));
        return response;
    }

    private MockHttpServletResponse runAs(RateLimitFilter filter, String username)
            throws ServletException, IOException {
        authenticateAs(username);
        try {
            return run(filter);
        } finally {
            SecurityContextHolder.clearContext();
        }
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

    private static final class InterleavingClock extends Clock {

        private final Instant oldMinute;
        private final Instant newMinute;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch oldMinuteSampled = new CountDownLatch(1);
        private final CountDownLatch secondClockCall = new CountDownLatch(1);
        private final CountDownLatch releaseOldMinute = new CountDownLatch(1);

        private InterleavingClock(Instant oldMinute, Instant newMinute) {
            this.oldMinute = oldMinute;
            this.newMinute = newMinute;
        }

        void awaitOldMinuteSampled() throws InterruptedException {
            assertThat(oldMinuteSampled.await(5, TimeUnit.SECONDS)).isTrue();
        }

        void awaitSecondRequestReadyOrClockCalled(AtomicReference<Thread> requestThread) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (secondClockCall.getCount() != 0) {
                Thread thread = requestThread.get();
                if (thread != null && thread.getState() == Thread.State.BLOCKED) {
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError("second request did not reach the rate-limit clock");
                }
                Thread.onSpinWait();
            }
        }

        void releaseOldMinuteRequest() {
            releaseOldMinute.countDown();
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
            if (calls.getAndIncrement() != 0) {
                secondClockCall.countDown();
                return newMinute;
            }
            oldMinuteSampled.countDown();
            try {
                if (!releaseOldMinute.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("old-minute request was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return oldMinute;
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
