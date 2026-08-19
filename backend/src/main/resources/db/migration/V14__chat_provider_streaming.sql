ALTER TABLE generation_run ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'deterministic';
ALTER TABLE generation_run ADD COLUMN first_token_latency_ms BIGINT NOT NULL DEFAULT 0;
