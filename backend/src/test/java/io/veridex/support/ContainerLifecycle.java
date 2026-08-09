package io.veridex.support;

import java.util.ArrayList;
import java.util.List;
import org.testcontainers.lifecycle.Startable;

final class ContainerLifecycle {

    private final List<Startable> startedContainers = new ArrayList<>();

    void start(Startable container) {
        try {
            container.start();
            startedContainers.add(container);
        } catch (RuntimeException | Error startupFailure) {
            try {
                container.stop();
            } catch (RuntimeException | Error currentCleanupFailure) {
                addSuppressedUnlessSame(startupFailure, currentCleanupFailure);
            }
            try {
                stopStartedContainers();
            } catch (RuntimeException | Error priorCleanupFailure) {
                addSuppressedUnlessSame(startupFailure, priorCleanupFailure);
                for (Throwable suppressed : priorCleanupFailure.getSuppressed()) {
                    addSuppressedUnlessSame(startupFailure, suppressed);
                }
            }
            throw startupFailure;
        }
    }

    void stopStartedContainers() {
        Throwable cleanupFailure = null;
        try {
            for (int index = startedContainers.size() - 1; index >= 0; index--) {
                try {
                    startedContainers.get(index).stop();
                } catch (RuntimeException | Error stopFailure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = stopFailure;
                    } else {
                        cleanupFailure.addSuppressed(stopFailure);
                    }
                }
            }
        } finally {
            startedContainers.clear();
        }
        rethrow(cleanupFailure);
    }

    private static void addSuppressedUnlessSame(Throwable primary, Throwable secondary) {
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
    }
}
