package io.veridex.ingestion.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class IngestionObservability {

    private static final Duration STUCK_AFTER = Duration.ofMinutes(15);

    public IngestionObservability(DocumentVersionProcessing documents, MeterRegistry meters) {
        Gauge.builder("veridex.ingestion.processing.stuck", documents,
                        source -> countStuck(source))
                .register(meters);
    }

    private static double countStuck(Object source) {
        try {
            return ((DocumentVersionProcessing) source)
                    .countProcessingSince(Instant.now().minus(STUCK_AFTER));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
