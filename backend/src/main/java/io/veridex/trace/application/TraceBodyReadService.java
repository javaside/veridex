package io.veridex.trace.application;

import io.veridex.shared.observability.MetricName;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import io.veridex.trace.api.TraceBodyResponse;
import io.veridex.trace.domain.TraceBody;
import io.veridex.trace.domain.TraceBodyRepository;
import io.veridex.trace.infrastructure.TraceBodyCrypto;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TraceBodyReadService {
    private static final Pattern REASON = Pattern.compile("[\\p{L}\\p{N} .,_:;()/#@+\\-]{1,200}");
    private final TraceBodyRepository repository;
    private final TraceBodyCrypto crypto;
    private final TraceBodyAccessAuditor auditor;
    private final JsonMapper json;
    private final VeridexObservability observability;
    private final Clock clock;

    @Autowired
    public TraceBodyReadService(TraceBodyRepository repository, TraceBodyCrypto crypto,
                                TraceBodyAccessAuditor auditor, JsonMapper json,
                                VeridexObservability observability) {
        this(repository, crypto, auditor, json, observability, Clock.systemUTC());
    }

    TraceBodyReadService(TraceBodyRepository repository, TraceBodyCrypto crypto,
                         TraceBodyAccessAuditor auditor, JsonMapper json, Clock clock) {
        this(repository, crypto, auditor, json, null, clock);
    }

    TraceBodyReadService(TraceBodyRepository repository, TraceBodyCrypto crypto,
                         TraceBodyAccessAuditor auditor, JsonMapper json,
                         VeridexObservability observability, Clock clock) {
        this.repository = repository;
        this.crypto = crypto;
        this.auditor = auditor;
        this.json = json;
        this.observability = observability;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public TraceBodyResponse read(UUID runId, UUID actorId, String reason, String requestId) {
        if (reason == null || !REASON.matcher(reason).matches()) {
            auditor.record(actorId, TraceBodyAccessAuditor.DENIED, runId, requestId, Map.of("code", "INVALID_REASON"));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid trace access reason");
        }
        TraceBody body = repository.findById(runId).orElse(null);
        if (body == null || !body.getExpiresAt().isAfter(clock.instant())) {
            if (body != null) repository.delete(body);
            auditor.record(actorId, TraceBodyAccessAuditor.NOT_FOUND, runId, requestId, Map.of("code", "NOT_FOUND"));
            increment(MetricName.TRACE_BODY_READ,
                    TelemetryTag.traceBodyOutcome(TelemetryOutcome.TraceBodyOutcome.NOT_FOUND));
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trace body not found");
        }
        try {
            byte[] plaintext = crypto.decrypt(runId, body.getSchemaVersion(), body.getEncryptionKeyId(),
                    body.getNonce(), body.getEncryptedBody());
            Object envelope = json.readValue(new String(plaintext, StandardCharsets.UTF_8), Object.class);
            auditor.record(actorId, TraceBodyAccessAuditor.READ, runId, requestId, Map.of("reason", reason));
            increment(MetricName.TRACE_BODY_READ,
                    TelemetryTag.traceBodyOutcome(TelemetryOutcome.TraceBodyOutcome.SUCCESS));
            return new TraceBodyResponse(runId, body.getCreatedAt(), body.getExpiresAt(), envelope);
        } catch (RuntimeException exception) {
            auditor.record(actorId, TraceBodyAccessAuditor.DECRYPT_FAILED, runId, requestId,
                    Map.of("code", "TRACE_BODY_UNAVAILABLE"));
            increment(MetricName.TRACE_BODY_READ,
                    TelemetryTag.traceBodyOutcome(TelemetryOutcome.TraceBodyOutcome.DECRYPT_FAILED));
            throw new TraceBodyReadFailure();
        }
    }

    private void increment(MetricName metric, TelemetryTag tag) {
        if (observability != null) {
            observability.increment(metric, tag);
        }
    }

    public static final class TraceBodyReadFailure extends RuntimeException {
    }
}
