package io.veridex.trace.application;

import io.veridex.trace.domain.TraceBody;
import io.veridex.trace.domain.TraceBodyRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TraceBodyWriter {
    private final TraceBodyRepository repository;

    public TraceBodyWriter(TraceBodyRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(UUID runId, String policy, String keyId, byte[] nonce, byte[] ciphertext,
                      short schemaVersion, Instant createdAt, Instant expiresAt) {
        repository.save(new TraceBody(runId, policy, ciphertext, keyId, nonce, schemaVersion, createdAt, expiresAt));
    }
}
