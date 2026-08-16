package io.veridex.trace.application;

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
    private final Clock clock;

    @Autowired
    public TraceBodyReadService(TraceBodyRepository repository, TraceBodyCrypto crypto,
                                TraceBodyAccessAuditor auditor, JsonMapper json) {
        this(repository, crypto, auditor, json, Clock.systemUTC());
    }

    TraceBodyReadService(TraceBodyRepository repository, TraceBodyCrypto crypto,
                         TraceBodyAccessAuditor auditor, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.crypto = crypto;
        this.auditor = auditor;
        this.json = json;
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "trace body not found");
        }
        try {
            byte[] plaintext = crypto.decrypt(runId, body.getSchemaVersion(), body.getEncryptionKeyId(),
                    body.getNonce(), body.getEncryptedBody());
            Object envelope = json.readValue(new String(plaintext, StandardCharsets.UTF_8), Object.class);
            auditor.record(actorId, TraceBodyAccessAuditor.READ, runId, requestId, Map.of("reason", reason));
            return new TraceBodyResponse(runId, body.getCreatedAt(), body.getExpiresAt(), envelope);
        } catch (RuntimeException exception) {
            auditor.record(actorId, TraceBodyAccessAuditor.DECRYPT_FAILED, runId, requestId,
                    Map.of("code", "TRACE_BODY_UNAVAILABLE"));
            throw new TraceBodyReadFailure();
        }
    }

    public static final class TraceBodyReadFailure extends RuntimeException {
    }
}
