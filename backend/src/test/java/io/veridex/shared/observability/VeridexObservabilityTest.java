package io.veridex.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

class VeridexObservabilityTest {

    @Test
    void recordsSuccessAndFailureTimersWithFixedTags() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        VeridexObservability observability = new VeridexObservability(meters, ObservationRegistry.create());

        try (var scope = observability.start(ObservationName.QA_RUN,
                TelemetryTag.conversation(TelemetryOutcome.Conversation.NEW))) {
            scope.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.COMPLETED));
        }
        try (var scope = observability.start(ObservationName.GENERATION_MODEL,
                TelemetryTag.provider("deterministic"), TelemetryTag.model("deterministic"))) {
            scope.failure(TelemetryErrorCode.MODEL_ERROR);
        }

        assertThat(meters.find("veridex.qa.run").tag("outcome", "completed").timer().count()).isEqualTo(1);
        assertThat(meters.find("veridex.generation.model").tag("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(meters.find("veridex.generation.model").tag("error_code", "model_error").timer().count()).isEqualTo(1);
    }

    @Test
    void recordingFailuresAreIgnored() {
        VeridexObservability observability = new VeridexObservability(new ThrowingMeterRegistry(), ObservationRegistry.create());

        observability.increment(MetricName.TRACE_BODY_CAPTURE);
        observability.record(MetricName.TRACE_BODY_CLEANUP, 1.0);
        try (var scope = observability.start(ObservationName.QA_RUN)) {
            scope.success(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.COMPLETED));
        }
    }

    private static final class ThrowingMeterRegistry extends SimpleMeterRegistry {
        @Override
        protected io.micrometer.core.instrument.Counter newCounter(
                io.micrometer.core.instrument.Meter.Id id) {
            throw new IllegalStateException("meter registry unavailable");
        }
    }
}
