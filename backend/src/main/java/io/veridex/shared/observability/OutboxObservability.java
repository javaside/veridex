package io.veridex.shared.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.veridex.shared.outbox.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class OutboxObservability {

    private final VeridexObservability observability;

    public OutboxObservability(OutboxEventRepository repository, MeterRegistry meters,
                               VeridexObservability observability) {
        this.observability = observability;
        Gauge.builder(MetricName.OUTBOX_PENDING.value(), repository, OutboxObservability::pending)
                .register(meters);
        Gauge.builder(MetricName.OUTBOX_OLDEST_UNPUBLISHED_AGE.value(), repository,
                        OutboxObservability::oldestAgeSeconds)
                .baseUnit("seconds")
                .register(meters);
    }

    public VeridexObservability.ObservationScope startPublish() {
        return observability.start(ObservationName.OUTBOX_PUBLISH);
    }

    public void recordPublishFailure() {
        observability.increment(MetricName.OUTBOX_PUBLISH_FAILURE);
    }

    private static double pending(OutboxEventRepository repository) {
        try {
            return repository.countByPublishedAtIsNull();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static double oldestAgeSeconds(OutboxEventRepository repository) {
        try {
            return repository.findOldestUnpublishedAt()
                    .map(oldest -> Math.max(0, Duration.between(oldest, Instant.now()).toSeconds()))
                    .orElse(0L);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
