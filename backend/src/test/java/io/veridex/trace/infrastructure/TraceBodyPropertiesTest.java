package io.veridex.trace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class TraceBodyPropertiesTest {

    @Test
    void hasSafeCanonicalDefaults() {
        TraceBodyProperties properties = new TraceBodyProperties();

        assertThat(properties.getCapturePolicy()).isEqualTo(TraceBodyProperties.CapturePolicy.NONE);
        assertThat(properties.getRetention()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.getMaxPlaintextSize()).isEqualTo(DataSize.ofKilobytes(256));
        assertThat(properties.getCleanupInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.getCleanupBatchSize()).isEqualTo(500);
        assertThat(properties.getFingerprintKey()).isEmpty();
    }

    @Test
    void acceptsOnlySupportedCapturePolicies() {
        TraceBodyProperties properties = new TraceBodyProperties();
        properties.setCapturePolicy("ERRORS");
        assertThat(properties.getCapturePolicy()).isEqualTo(TraceBodyProperties.CapturePolicy.ERRORS);
        properties.setCapturePolicy("ALL");
        assertThat(properties.getCapturePolicy()).isEqualTo(TraceBodyProperties.CapturePolicy.ALL);
        assertThatThrownBy(() -> properties.setCapturePolicy("sometimes"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noneModeDoesNotRequireAKeyRing() {
        TraceBodyProperties properties = new TraceBodyProperties();
        assertThat(new TraceBodyKeyRing(properties).isEmpty()).isTrue();
    }

    @Test
    void enabledModeRequiresValidCurrentKeyInRing() {
        TraceBodyProperties properties = new TraceBodyProperties();
        properties.setCapturePolicy("ALL");
        assertThatThrownBy(() -> new TraceBodyKeyRing(properties))
                .isInstanceOf(IllegalStateException.class);

        properties.setCurrentKeyId("current");
        properties.setCurrentKey(java.util.Base64.getEncoder().encodeToString(new byte[31]));
        properties.setHistoricalKeys("current=" + properties.getCurrentKey());
        assertThatThrownBy(() -> new TraceBodyKeyRing(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accepts32ByteCurrentAndHistoricalKeys() {
        TraceBodyProperties properties = new TraceBodyProperties();
        properties.setCapturePolicy("ERRORS");
        properties.setCurrentKeyId("current");
        properties.setCurrentKey(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        properties.setHistoricalKeys("current=" + properties.getCurrentKey() + ",old=" + java.util.Base64.getEncoder().encodeToString(new byte[32]));

        TraceBodyKeyRing ring = new TraceBodyKeyRing(properties);
        assertThat(ring.currentKeyId()).isEqualTo("current");
        assertThat(ring.key("old")).hasSize(32);
        assertThat(ring.key("current")).hasSize(32);
    }

    @Test
    void rejectsMalformedRingEntriesAndDuplicateIds() {
        TraceBodyProperties properties = new TraceBodyProperties();
        properties.setCapturePolicy("ALL");
        properties.setCurrentKeyId("current");
        properties.setCurrentKey(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        properties.setHistoricalKeys("bad-entry");
        assertThatThrownBy(() -> new TraceBodyKeyRing(properties)).isInstanceOf(IllegalStateException.class);

        properties.setHistoricalKeys("current=" + properties.getCurrentKey() + ",current=" + properties.getCurrentKey());
        assertThatThrownBy(() -> new TraceBodyKeyRing(properties)).isInstanceOf(IllegalStateException.class);

        properties.setHistoricalKeys("bad/id=" + properties.getCurrentKey());
        assertThatThrownBy(() -> new TraceBodyKeyRing(properties)).isInstanceOf(IllegalStateException.class);
    }
}
