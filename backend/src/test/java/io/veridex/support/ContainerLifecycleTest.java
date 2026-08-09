package io.veridex.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.lifecycle.Startable;

class ContainerLifecycleTest {

    @Test
    void startupRuntimeFailureStopsCurrentContainerThenPreviouslyStartedContainersInReverseOrder() {
        var events = new ArrayList<String>();
        var lifecycle = new ContainerLifecycle();
        var first = container("first", events, null, null);
        var second = container("second", events, null, null);
        var startupFailure = new IllegalStateException("startup");
        var failing = container("failing", events, startupFailure, null);

        lifecycle.start(first);
        lifecycle.start(second);

        assertThatThrownBy(() -> lifecycle.start(failing)).isSameAs(startupFailure);
        assertThat(events).containsExactly(
                "start:first", "start:second", "start:failing", "stop:failing", "stop:second", "stop:first");

        events.clear();
        lifecycle.stopStartedContainers();
        assertThat(events).isEmpty();
    }

    @Test
    void startupErrorRetainsRootCauseAndSuppressesEveryCleanupFailure() {
        var events = new ArrayList<String>();
        var lifecycle = new ContainerLifecycle();
        var firstStopFailure = new IllegalArgumentException("first stop");
        var secondStopFailure = new IllegalStateException("second stop");
        var currentStopFailure = new AssertionError("current stop");
        var startupFailure = new AssertionError("startup");

        lifecycle.start(container("first", events, null, firstStopFailure));
        lifecycle.start(container("second", events, null, secondStopFailure));

        assertThatThrownBy(() -> lifecycle.start(container("failing", events, startupFailure, currentStopFailure)))
                .isSameAs(startupFailure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed())
                        .containsExactly(currentStopFailure, secondStopFailure, firstStopFailure));
        assertThat(events).containsExactly(
                "start:first", "start:second", "start:failing", "stop:failing", "stop:second", "stop:first");
    }

    @Test
    void stopErrorDoesNotPreventRemainingStopsAndTheStartedListIsAlwaysCleared() {
        var events = new ArrayList<String>();
        var lifecycle = new ContainerLifecycle();
        var lastStopFailure = new AssertionError("last stop");
        var firstStopFailure = new IllegalStateException("first stop");

        lifecycle.start(container("first", events, null, firstStopFailure));
        lifecycle.start(container("middle", events, null, null));
        lifecycle.start(container("last", events, null, lastStopFailure));
        events.clear();

        assertThatThrownBy(lifecycle::stopStartedContainers)
                .isSameAs(lastStopFailure)
                .isInstanceOf(AssertionError.class)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(firstStopFailure));
        assertThat(events).containsExactly("stop:last", "stop:middle", "stop:first");

        events.clear();
        lifecycle.stopStartedContainers();
        assertThat(events).isEmpty();
    }

    @Test
    void cleanupReusingStartupFailureCannotReplaceTheStartupRootCause() {
        var events = new ArrayList<String>();
        var lifecycle = new ContainerLifecycle();
        var startupFailure = new AssertionError("shared failure");

        assertThatThrownBy(() -> lifecycle.start(container("failing", events, startupFailure, startupFailure)))
                .isSameAs(startupFailure);
        assertThat(events).containsExactly("start:failing", "stop:failing");
    }

    @Test
    void openSearchHealthClientAndRequestHaveFiniteTimeouts() {
        assertThat(InfrastructureSmokeTest.openSearchHttpClient().connectTimeout())
                .contains(Duration.ofSeconds(10));
        assertThat(InfrastructureSmokeTest.openSearchHealthRequest(URI.create("http://localhost:9200")).timeout())
                .contains(Duration.ofSeconds(10));
    }

    private static Startable container(
            String name, List<String> events, Throwable startupFailure, Throwable stopFailure) {
        return new Startable() {
            @Override
            public void start() {
                events.add("start:" + name);
                throwIfPresent(startupFailure);
            }

            @Override
            public void stop() {
                events.add("stop:" + name);
                throwIfPresent(stopFailure);
            }
        };
    }

    private static void throwIfPresent(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
    }
}
