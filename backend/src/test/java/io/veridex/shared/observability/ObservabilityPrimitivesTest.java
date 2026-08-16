package io.veridex.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObservabilityPrimitivesTest {

    @Test
    void exposesOnlyFixedNamesAndNormalizedValues() {
        assertThat(ObservationName.QA_RUN.value()).isEqualTo("veridex.qa.run");
        assertThat(MetricName.TRACE_BODY_CAPTURE.value()).isEqualTo("veridex.trace.body.capture");
        assertThat(TelemetryTag.qaOutcome(TelemetryOutcome.Qa.COMPLETED).value()).isEqualTo("completed");
        assertThat(TelemetryTag.ingestionStage(TelemetryOutcome.IngestionStage.MESSAGE_DECODE).value())
                .isEqualTo("message_decode");
    }

    @Test
    void modelNamesAreBounded() {
        BoundedModelTags tags = new BoundedModelTags(Set.of("deterministic"));

        assertThat(tags.resolve("deterministic").model()).isEqualTo("deterministic");
        assertThat(tags.resolve("tenant-secret-model").model()).isEqualTo("unknown");
    }

    @Test
    void telemetryTagDoesNotExposeArbitraryPublicConstruction() {
        assertThat(TelemetryTag.class.getConstructors()).isEmpty();
        assertThat(TelemetryTag.class.getDeclaredConstructors())
                .allMatch(constructor -> !isArbitraryPair(constructor));
    }

    private boolean isArbitraryPair(Constructor<?> constructor) {
        return java.lang.reflect.Modifier.isPublic(constructor.getModifiers())
                && java.util.Arrays.equals(constructor.getParameterTypes(), new Class<?>[] {String.class, String.class});
    }
}
