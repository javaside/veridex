ALTER TABLE query_run ADD COLUMN question_fingerprint VARCHAR(64);

UPDATE query_run
SET question = '[REDACTED]',
    normalized_question = '[REDACTED]',
    error = NULL;

ALTER TABLE outbox_event ADD COLUMN traceparent VARCHAR(100);
ALTER TABLE outbox_event ADD COLUMN tracestate VARCHAR(512);
ALTER TABLE outbox_event ADD COLUMN request_id VARCHAR(100);

ALTER TABLE document_version ADD COLUMN processing_started_at TIMESTAMPTZ;

CREATE TABLE trace_body (
    query_run_id UUID PRIMARY KEY REFERENCES query_run(id) ON DELETE CASCADE,
    capture_policy VARCHAR(20) NOT NULL,
    encrypted_body BYTEA NOT NULL,
    encryption_key_id VARCHAR(100) NOT NULL,
    nonce BYTEA NOT NULL,
    schema_version SMALLINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_trace_body_expires_at ON trace_body (expires_at);
