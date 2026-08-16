package io.veridex.trace.application;

import io.veridex.shared.observability.MetricName;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import io.veridex.trace.infrastructure.JdbcTraceBodyMaintenanceRepository;
import io.veridex.trace.infrastructure.TraceBodyProperties;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TraceBodyCleanupJob {

    static final int MAX_BATCHES_PER_INVOCATION = 10;

    private final JdbcTraceBodyMaintenanceRepository maintenance;
    private final TraceBodyProperties properties;
    private final VeridexObservability observability;
    private final Clock clock;

    @Autowired
    public TraceBodyCleanupJob(JdbcTraceBodyMaintenanceRepository maintenance, TraceBodyProperties properties,
                               VeridexObservability observability) {
        this(maintenance, properties, observability, Clock.systemUTC());
    }

    TraceBodyCleanupJob(JdbcTraceBodyMaintenanceRepository maintenance, TraceBodyProperties properties,
                        VeridexObservability observability, Clock clock) {
        this.maintenance = maintenance;
        this.properties = properties;
        this.observability = observability;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${veridex.trace.body.cleanup-interval:1h}")
    public void cleanup() {
        int batchSize = properties.getCleanupBatchSize();
        if (batchSize <= 0) {
            record(0, TelemetryOutcome.TraceBodyOutcome.FAILURE,
                    TelemetryOutcome.TraceBodyCleanupReason.INVALID_CONFIGURATION);
            return;
        }
        runBounded(() -> maintenance.deleteExpired(clock.instant(), batchSize), batchSize,
                TelemetryOutcome.TraceBodyCleanupReason.EXPIRED);
        runBounded(() -> maintenance.scrubQueryRuns(batchSize), batchSize,
                TelemetryOutcome.TraceBodyCleanupReason.QUERY_RUN_SCRUB);
    }

    private void runBounded(BatchOperation operation, int batchSize,
                            TelemetryOutcome.TraceBodyCleanupReason reason) {
        int changed = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_INVOCATION; batch++) {
                int current = operation.execute();
                changed += current;
                if (current < batchSize) {
                    break;
                }
            }
            record(changed, TelemetryOutcome.TraceBodyOutcome.SUCCESS, reason);
        } catch (RuntimeException ignored) {
            record(changed, TelemetryOutcome.TraceBodyOutcome.FAILURE,
                    TelemetryOutcome.TraceBodyCleanupReason.MAINTENANCE_FAILURE);
        }
    }

    private void record(int count, TelemetryOutcome.TraceBodyOutcome outcome,
                        TelemetryOutcome.TraceBodyCleanupReason reason) {
        observability.recordCount(MetricName.TRACE_BODY_CLEANUP, count,
                TelemetryTag.cleanupOutcome(outcome), TelemetryTag.cleanupReason(reason));
    }

    @FunctionalInterface
    private interface BatchOperation {
        int execute();
    }
}
