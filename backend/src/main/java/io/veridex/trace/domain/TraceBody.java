package io.veridex.trace.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "trace_body")
public class TraceBody {
    @Id
    @Column(name = "query_run_id")
    private UUID queryRunId;
    @Column(name = "capture_policy", nullable = false, length = 20)
    private String capturePolicy;
    @Column(name = "encrypted_body", nullable = false, columnDefinition = "bytea")
    private byte[] encryptedBody;
    @Column(name = "encryption_key_id", nullable = false, length = 100)
    private String encryptionKeyId;
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] nonce;
    @Column(name = "schema_version", nullable = false)
    private short schemaVersion;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected TraceBody() { }

    public TraceBody(UUID queryRunId, String capturePolicy, byte[] encryptedBody, String encryptionKeyId,
                     byte[] nonce, short schemaVersion, Instant createdAt, Instant expiresAt) {
        this.queryRunId = queryRunId;
        this.capturePolicy = capturePolicy;
        this.encryptedBody = encryptedBody.clone();
        this.encryptionKeyId = encryptionKeyId;
        this.nonce = nonce.clone();
        this.schemaVersion = schemaVersion;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getQueryRunId() { return queryRunId; }
    public String getCapturePolicy() { return capturePolicy; }
    public byte[] getEncryptedBody() { return encryptedBody.clone(); }
    public String getEncryptionKeyId() { return encryptionKeyId; }
    public byte[] getNonce() { return nonce.clone(); }
    public short getSchemaVersion() { return schemaVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
