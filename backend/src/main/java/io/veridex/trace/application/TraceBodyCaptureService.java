package io.veridex.trace.application;

import io.veridex.trace.api.TraceBodyCapture;
import io.veridex.trace.infrastructure.TraceBodyCrypto;
import io.veridex.trace.infrastructure.TraceBodyProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TraceBodyCaptureService implements TraceBodyCapture {
    private static final short SCHEMA_VERSION = 1;
    private static final String SKIP_CODE = "TRACE_BODY_CAPTURE_SKIPPED";
    private final TraceBodyProperties properties;
    private final TraceBodyCrypto crypto;
    private final TraceBodyWriter writer;
    private final JsonMapper json;
    private final Clock clock;

    @Autowired
    public TraceBodyCaptureService(TraceBodyProperties properties, TraceBodyCrypto crypto,
                                   TraceBodyWriter writer, JsonMapper json) {
        this(properties, crypto, writer, json, Clock.systemUTC());
    }

    TraceBodyCaptureService(TraceBodyProperties properties, TraceBodyCrypto crypto, TraceBodyWriter writer,
                            JsonMapper json, Clock clock) {
        this.properties = properties;
        this.crypto = crypto;
        this.writer = writer;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void capture(UUID runId, TerminalOutcome outcome, String errorCode, TraceBodyMaterial material) {
        if (!shouldCapture(outcome)) return;
        try {
            byte[] plaintext = json.writeValueAsBytes(new Envelope(outcome, boundedCode(errorCode), material));
            if (plaintext.length > properties.getMaxPlaintextSize().toBytes()) return;
            Instant now = clock.instant();
            var encrypted = crypto.encrypt(runId, SCHEMA_VERSION, plaintext);
            writer.write(runId, properties.getCapturePolicy().name(), encrypted.keyId(), encrypted.nonce(),
                    encrypted.ciphertext(), SCHEMA_VERSION, now, now.plus(properties.getRetention()));
        } catch (RuntimeException ignored) {
            // Capture is diagnostic and must never affect the user-visible QA result.
        }
    }

    private boolean shouldCapture(TerminalOutcome outcome) {
        return properties.getCapturePolicy() == TraceBodyProperties.CapturePolicy.ALL
                || (properties.getCapturePolicy() == TraceBodyProperties.CapturePolicy.ERRORS
                && outcome != TerminalOutcome.COMPLETED);
    }

    private static String boundedCode(String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,64}")) return SKIP_CODE;
        return errorCode;
    }

    private record Envelope(TerminalOutcome outcome, String errorCode, TraceBodyMaterial material) { }
}
