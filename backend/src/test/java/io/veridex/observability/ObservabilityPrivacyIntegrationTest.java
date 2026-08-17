package io.veridex.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.veridex.shared.observability.MetricName;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ObservabilityPrivacyIntegrationTest {

    @Autowired MeterRegistry meters;
    @Autowired VeridexObservability observability;

    @Test
    void businessTelemetryContainsNoContentOrIdentifierLabels() {
        UUID runId = UUID.randomUUID();
        String sentinel = "SENSITIVE_QUESTION_5B";

        observability.increment(MetricName.INGESTION_ACK,
                TelemetryTag.ingestionOutcome(TelemetryOutcome.Ingestion.SUCCESS));
        observability.increment(MetricName.TRACE_BODY_CAPTURE_SKIPPED,
                TelemetryTag.skipReason(TelemetryOutcome.TraceBodySkipReason.POLICY_DISABLED));

        String prometheus = io.micrometer.prometheusmetrics.PrometheusMeterRegistry.class
                .cast(meters).scrape();
        assertThat(prometheus).doesNotContain(sentinel, runId.toString(), "prompt", "completion");
        assertThat(meters.get("veridex.ingestion.ack").counter().getId().getTags())
                .extracting(tag -> tag.getKey())
                .containsExactly("application", "environment", "result");
        assertThat(meters.get("veridex.ingestion.ack").counter().getId().getTags())
                .extracting(tag -> tag.getValue())
                .doesNotContain(runId.toString(), sentinel);
    }

    @Test
    void fixedTagSurfaceDoesNotExposeUuidOrContinuousValues() {
        Set<String> forbiddenKeys = Set.of("runId", "requestId", "userId", "documentId", "releaseId", "question");
        meters.getMeters().forEach(meter -> meter.getId().getTags()
                .forEach(tag -> assertThat(tag.getKey()).isNotIn(forbiddenKeys)));
    }
}
