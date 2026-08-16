package io.veridex.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.veridex.shared.observability.VeridexObservability;
import io.veridex.trace.api.TraceBodyCapture;
import io.veridex.trace.application.TraceBodyCaptureService;
import io.veridex.trace.application.TraceBodyWriter;
import io.veridex.trace.infrastructure.TraceBodyCrypto;
import io.veridex.trace.infrastructure.TraceBodyProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class TraceBodyCaptureServiceTest {
    @Mock TraceBodyCrypto crypto;
    @Mock TraceBodyWriter writer;

    private TraceBodyProperties properties(TraceBodyProperties.CapturePolicy policy, long maxBytes) {
        var properties = new TraceBodyProperties();
        properties.setCapturePolicy(policy);
        properties.setMaxPlaintextSize(DataSize.ofBytes(maxBytes));
        properties.setCurrentKeyId("current");
        properties.setRetention(java.time.Duration.ofHours(1));
        return properties;
    }

    private TraceBodyCapture.TraceBodyMaterial material(String answer) {
        return new TraceBodyCapture.TraceBodyMaterial("question", List.of(), answer, List.of(), List.of());
    }

    @Test
    void noneDoesNotSerializeOrWrite() {
        var service = new TraceBodyCaptureService(properties(TraceBodyProperties.CapturePolicy.NONE, 256),
                crypto, writer, new JsonMapper());
        service.capture(UUID.randomUUID(), TraceBodyCapture.TerminalOutcome.COMPLETED, null, material("answer"));
        verify(writer, never()).write(any(), any(), any(), any(), any(), anyShort(), any(), any());
    }

    @Test
    void errorsCaptureOnlyNonCompletedOutcomes() {
        var properties = properties(TraceBodyProperties.CapturePolicy.ERRORS, 4096);
        when(crypto.encrypt(any(), anyShort(), any())).thenReturn(
                new TraceBodyCrypto.EncryptedPayload("current", new byte[12], new byte[]{1}));
        var service = new TraceBodyCaptureService(properties, crypto, writer, new JsonMapper());
        service.capture(UUID.randomUUID(), TraceBodyCapture.TerminalOutcome.COMPLETED, null, material("answer"));
        service.capture(UUID.randomUUID(), TraceBodyCapture.TerminalOutcome.FAILED, "MODEL_ERROR", material(null));
        verify(writer).write(any(), any(), any(), any(), any(), anyShort(), any(), any());
    }

    @Test
    void writerFailureIsFailOpen() {
        var properties = properties(TraceBodyProperties.CapturePolicy.ALL, 4096);
        when(crypto.encrypt(any(), anyShort(), any())).thenReturn(
                new TraceBodyCrypto.EncryptedPayload("current", new byte[12], new byte[]{1}));
        org.mockito.Mockito.doThrow(new RuntimeException("storage details"))
                .when(writer).write(any(), any(), any(), any(), any(), anyShort(), any(), any());
        var service = new TraceBodyCaptureService(properties, crypto, writer, new JsonMapper());
        service.capture(UUID.randomUUID(), TraceBodyCapture.TerminalOutcome.COMPLETED, null, material("answer"));
    }

    @Test
    void recordsOnlyFixedCaptureAndSkipTelemetry() {
        var meters = new SimpleMeterRegistry();
        var observability = new VeridexObservability(meters, ObservationRegistry.create());
        var all = properties(TraceBodyProperties.CapturePolicy.ALL, 4096);
        when(crypto.encrypt(any(), anyShort(), any())).thenReturn(
                new TraceBodyCrypto.EncryptedPayload("current", new byte[12], new byte[]{1}));
        var captured = new TraceBodyCaptureService(all, crypto, writer, new JsonMapper(), observability);

        captured.capture(UUID.randomUUID(), TraceBodyCapture.TerminalOutcome.COMPLETED, null, material("answer"));
        var disabled = new TraceBodyCaptureService(properties(TraceBodyProperties.CapturePolicy.NONE, 4096),
                crypto, writer, new JsonMapper(), observability);
        disabled.capture(UUID.randomUUID(), TraceBodyCapture.TerminalOutcome.FAILED, "secret-error", material("answer"));

        assertThat(meters.find("veridex.trace.body.capture").tag("outcome", "success").counter().count())
                .isEqualTo(1);
        assertThat(meters.find("veridex.trace.body.capture.skipped").tag("reason", "policy_disabled")
                .counter().count()).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .noneMatch(tag -> tag.getValue().contains("secret")));
    }
}
